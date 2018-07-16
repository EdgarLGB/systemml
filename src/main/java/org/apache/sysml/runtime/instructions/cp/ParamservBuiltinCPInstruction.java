/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.instructions.cp;

import static org.apache.sysml.parser.Statement.PSFrequency;
import static org.apache.sysml.parser.Statement.PSModeType;
import static org.apache.sysml.parser.Statement.PSScheme;
import static org.apache.sysml.parser.Statement.PSUpdateType;
import static org.apache.sysml.parser.Statement.PS_AGGREGATION_FUN;
import static org.apache.sysml.parser.Statement.PS_BATCH_SIZE;
import static org.apache.sysml.parser.Statement.PS_EPOCHS;
import static org.apache.sysml.parser.Statement.PS_FEATURES;
import static org.apache.sysml.parser.Statement.PS_FREQUENCY;
import static org.apache.sysml.parser.Statement.PS_HYPER_PARAMS;
import static org.apache.sysml.parser.Statement.PS_LABELS;
import static org.apache.sysml.parser.Statement.PS_MODE;
import static org.apache.sysml.parser.Statement.PS_MODEL;
import static org.apache.sysml.parser.Statement.PS_PARALLELISM;
import static org.apache.sysml.parser.Statement.PS_SCHEME;
import static org.apache.sysml.parser.Statement.PS_UPDATE_FUN;
import static org.apache.sysml.parser.Statement.PS_UPDATE_TYPE;
import static org.apache.sysml.parser.Statement.PS_VAL_FEATURES;
import static org.apache.sysml.parser.Statement.PS_VAL_LABELS;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.concurrent.BasicThreadFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.network.server.TransportServer;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.LocalVariableMap;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.controlprogram.paramserv.DataPartitionScheme;
import org.apache.sysml.runtime.controlprogram.paramserv.DataPartitioner;
import org.apache.sysml.runtime.controlprogram.paramserv.LocalPSWorker;
import org.apache.sysml.runtime.controlprogram.paramserv.LocalParamServer;
import org.apache.sysml.runtime.controlprogram.paramserv.ParamServer;
import org.apache.sysml.runtime.controlprogram.paramserv.ParamservUtils;
import org.apache.sysml.runtime.controlprogram.paramserv.spark.SparkPSBody;
import org.apache.sysml.runtime.controlprogram.paramserv.spark.SparkPSWorker;
import org.apache.sysml.runtime.controlprogram.paramserv.spark.rpc.PSRpcFactory;
import org.apache.sysml.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.apache.sysml.runtime.controlprogram.parfor.stat.Timing;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.util.ProgramConverter;
import org.apache.sysml.utils.Statistics;

public class ParamservBuiltinCPInstruction extends ParameterizedBuiltinCPInstruction {

	private static final int DEFAULT_BATCH_SIZE = 64;
	private static final PSFrequency DEFAULT_UPDATE_FREQUENCY = PSFrequency.BATCH;
	private static final PSScheme DEFAULT_SCHEME = PSScheme.DISJOINT_CONTIGUOUS;

	//internal local debug level
	private static final boolean LDEBUG = false;
	protected static final Log LOG = LogFactory.getLog(ParamservBuiltinCPInstruction.class.getName());


	static {
		// for internal debugging only
		if (LDEBUG) {
			Logger.getLogger("org.apache.sysml.runtime.controlprogram.paramserv").setLevel(Level.DEBUG);
			Logger.getLogger(ParamservBuiltinCPInstruction.class.getName()).setLevel(Level.DEBUG);
		}
	}

	public ParamservBuiltinCPInstruction(Operator op, LinkedHashMap<String, String> paramsMap, CPOperand out, String opcode, String istr) {
		super(op, paramsMap, out, opcode, istr);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		PSModeType mode = getPSMode();
		switch (mode) {
			case LOCAL:
				runLocally(ec, mode);
				break;
			case REMOTE_SPARK:
				runOnSpark((SparkExecutionContext) ec, mode);
				break;
		}
	}

	private void runOnSpark(SparkExecutionContext sec, PSModeType mode) {
		PSScheme scheme = getScheme();
		int workerNum = getWorkerNum(mode);
		String updFunc = getParam(PS_UPDATE_FUN);
		String aggFunc = getParam(PS_AGGREGATION_FUN);

		int k = getParLevel(workerNum);

		// Get the compiled execution context
		LocalVariableMap newVarsMap = createVarsMap(sec);
		ExecutionContext newEC = ParamservUtils.createExecutionContext(sec, newVarsMap, updFunc, aggFunc, k);

		MatrixObject features = sec.getMatrixObject(getParam(PS_FEATURES));
		MatrixObject labels = sec.getMatrixObject(getParam(PS_LABELS));

		// Force all the instructions to CP type
		ParamservUtils.recompileToCP(newEC.getProgram());

		// Serialize all the needed params for remote workers
		String host = sec.getSparkContext().getConf().get("spark.driver.host");
		SparkPSBody body = new SparkPSBody(newEC, host);
		HashMap<String, byte[]> clsMap = new HashMap<>();
		String program = ProgramConverter.serializeSparkPSBody(body, clsMap);

		// Create remote workers
		SparkPSWorker worker = new SparkPSWorker(getParam(PS_UPDATE_FUN), getFrequency(), getEpochs(), getBatchSize(), program, clsMap);

		// Create the agg service's execution context
		ExecutionContext aggServiceEC = ParamservUtils.copyExecutionContext(newEC, 1).get(0);

		// Create the parameter server
		ListObject model = sec.getListObject(getParam(PS_MODEL));
		ParamServer ps = createPS(mode, aggFunc, getUpdateType(), workerNum, model, aggServiceEC);

		// Create the netty server for ps
		TransportServer server = PSRpcFactory.createServer((LocalParamServer) ps, host);	// Start the server

		ParamservUtils.doPartitionOnSpark(sec, features, labels, scheme, workerNum) // Do data partitioning
			.foreach(worker);   // Run remote workers

		// Stop the netty server
		server.close();
	}

	private void runLocally(ExecutionContext ec, PSModeType mode) {
		Timing tSetup = DMLScript.STATISTICS ? new Timing(true) : null;
		int workerNum = getWorkerNum(mode);
		BasicThreadFactory factory = new BasicThreadFactory.Builder()
			.namingPattern("workers-pool-thread-%d").build();
		ExecutorService es = Executors.newFixedThreadPool(workerNum, factory);
		String updFunc = getParam(PS_UPDATE_FUN);
		String aggFunc = getParam(PS_AGGREGATION_FUN);

		int k = getParLevel(workerNum);

		// Get the compiled execution context
		LocalVariableMap newVarsMap = createVarsMap(ec);
		ExecutionContext newEC = ParamservUtils.createExecutionContext(ec, newVarsMap, updFunc, aggFunc, k);

		// Create workers' execution context
		List<ExecutionContext> workerECs = ParamservUtils.copyExecutionContext(newEC, workerNum);

		// Create the agg service's execution context
		ExecutionContext aggServiceEC = ParamservUtils.copyExecutionContext(newEC, 1).get(0);

		PSFrequency freq = getFrequency();
		PSUpdateType updateType = getUpdateType();
		int epochs = getEpochs();

		// Create the parameter server
		ListObject model = ec.getListObject(getParam(PS_MODEL));
		ParamServer ps = createPS(mode, aggFunc, updateType, workerNum, model, aggServiceEC);

		// Create the local workers
		MatrixObject valFeatures = ec.getMatrixObject(getParam(PS_VAL_FEATURES));
		MatrixObject valLabels = ec.getMatrixObject(getParam(PS_VAL_LABELS));
		List<LocalPSWorker> workers = IntStream.range(0, workerNum)
		   .mapToObj(i -> new LocalPSWorker(i, updFunc, freq, epochs, getBatchSize(), valFeatures, valLabels, workerECs.get(i), ps))
		   .collect(Collectors.toList());

		// Do data partition
		PSScheme scheme = getScheme();
		partitionLocally(scheme, ec, workers);

		if (DMLScript.STATISTICS)
			Statistics.accPSSetupTime((long) tSetup.stop());

		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("\nConfiguration of paramserv func: "
				+ "\nmode: %s \nworkerNum: %d \nupdate frequency: %s "
				+ "\nstrategy: %s \ndata partitioner: %s",
				mode, workerNum, freq, updateType, scheme));
		}

		try {
			// Launch the worker threads and wait for completion
			for (Future<Void> ret : es.invokeAll(workers))
				ret.get(); //error handling
			// Fetch the final model from ps
			ListObject result = ps.getResult();
			ec.setVariable(output.getName(), result);
		} catch (InterruptedException | ExecutionException e) {
			throw new DMLRuntimeException("ParamservBuiltinCPInstruction: some error occurred: ", e);
		} finally {
			es.shutdownNow();
		}
	}

	private LocalVariableMap createVarsMap(ExecutionContext ec) {
		// Put the hyperparam into the variables table
		LocalVariableMap varsMap = new LocalVariableMap();
		ListObject hyperParams = getHyperParams(ec);
		if (hyperParams != null) {
			varsMap.put(PS_HYPER_PARAMS, hyperParams);
		}
		return varsMap;
	}

	private PSModeType getPSMode() {
		PSModeType mode;
		try {
			mode = PSModeType.valueOf(getParam(PS_MODE));
		} catch (IllegalArgumentException e) {
			throw new DMLRuntimeException(String.format("Paramserv function: not support ps execution mode '%s'", getParam(PS_MODE)));
		}
		return mode;
	}

	private int getEpochs() {
		int epochs = Integer.valueOf(getParam(PS_EPOCHS));
		if (epochs <= 0) {
			throw new DMLRuntimeException(String.format("Paramserv function: "
				+ "The argument '%s' could not be less than or equal to 0.", PS_EPOCHS));
		}
		return epochs;
	}

	private int getParLevel(int workerNum) {
		return Math.max((int)Math.ceil((double)getRemainingCores()/workerNum), 1);
	}

	private PSUpdateType getUpdateType() {
		PSUpdateType updType;
		try {
			updType = PSUpdateType.valueOf(getParam(PS_UPDATE_TYPE));
		} catch (IllegalArgumentException e) {
			throw new DMLRuntimeException(String.format("Paramserv function: not support update type '%s'.", getParam(PS_UPDATE_TYPE)));
		}
		if (updType == PSUpdateType.SSP)
			throw new DMLRuntimeException("Not support update type SSP.");
		return updType;
	}

	private PSFrequency getFrequency() {
		if (!getParameterMap().containsKey(PS_FREQUENCY)) {
			return DEFAULT_UPDATE_FREQUENCY;
		}
		try {
			return PSFrequency.valueOf(getParam(PS_FREQUENCY));
		} catch (IllegalArgumentException e) {
			throw new DMLRuntimeException(String.format("Paramserv function: "
				+ "not support '%s' update frequency.", getParam(PS_FREQUENCY)));
		}
	}

	private int getRemainingCores() {
		return InfrastructureAnalyzer.getLocalParallelism() - 1;
	}

	/**
	 * Get the worker numbers according to the vcores
	 *
	 * @param mode execution mode
	 * @return worker numbers
	 */
	private int getWorkerNum(PSModeType mode) {
		switch (mode) {
			case LOCAL:
				// default worker number: available cores - 1 (assign one process for agg service)
				return getParameterMap().containsKey(PS_PARALLELISM) ?
					Integer.valueOf(getParam(PS_PARALLELISM)) : getRemainingCores();
			case REMOTE_SPARK:
				return getParameterMap().containsKey(PS_PARALLELISM) ?
					Integer.valueOf(getParam(PS_PARALLELISM)) : SparkExecutionContext.getDefaultParallelism(true);
			default:
				throw new DMLRuntimeException("Unsupported parameter server: " + mode.name());
		}
	}

	/**
	 * Create a server which serves the local or remote workers
	 *
	 * @return parameter server
	 */
	private ParamServer createPS(PSModeType mode, String aggFunc, PSUpdateType updateType, int workerNum, ListObject model, ExecutionContext ec) {
		switch (mode) {
			case LOCAL:
			case REMOTE_SPARK:
				return new LocalParamServer(model, aggFunc, updateType, ec, workerNum);
			default:
				throw new DMLRuntimeException("Unsupported parameter server: "+mode.name());
		}
	}

	private long getBatchSize() {
		if (!getParameterMap().containsKey(PS_BATCH_SIZE)) {
			return DEFAULT_BATCH_SIZE;
		}
		long batchSize = Integer.valueOf(getParam(PS_BATCH_SIZE));
		if (batchSize <= 0) {
			throw new DMLRuntimeException(String.format("Paramserv function: the number "
				+ "of argument '%s' could not be less than or equal to 0.", PS_BATCH_SIZE));
		}
		return batchSize;
	}

	private ListObject getHyperParams(ExecutionContext ec) {
		ListObject hyperparams = null;
		if (getParameterMap().containsKey(PS_HYPER_PARAMS)) {
			hyperparams = ec.getListObject(getParam(PS_HYPER_PARAMS));
		}
		return hyperparams;
	}

	private void partitionLocally(PSScheme scheme, ExecutionContext ec, List<LocalPSWorker> workers) {
		MatrixObject features = ec.getMatrixObject(getParam(PS_FEATURES));
		MatrixObject labels = ec.getMatrixObject(getParam(PS_LABELS));
		DataPartitionScheme.Result result = new DataPartitioner(scheme).doPartitioning(workers.size(), features.acquireRead(), labels.acquireRead());
		features.release();
		labels.release();
		List<MatrixObject> pfs = result.pFeatures;
		List<MatrixObject> pls = result.pLabels;
		if (pfs.size() < workers.size()) {
			if (LOG.isWarnEnabled()) {
				LOG.warn(String.format("There is only %d batches of data but has %d workers. "
					+ "Hence, reset the number of workers with %d.", pfs.size(), workers.size(), pfs.size()));
			}
			workers = workers.subList(0, pfs.size());
		}
		for (int i = 0; i < workers.size(); i++) {
			workers.get(i).setFeatures(pfs.get(i));
			workers.get(i).setLabels(pls.get(i));
		}
	}

	private PSScheme getScheme() {
		PSScheme scheme = DEFAULT_SCHEME;
		if (getParameterMap().containsKey(PS_SCHEME)) {
			try {
				scheme = PSScheme.valueOf(getParam(PS_SCHEME));
			} catch (IllegalArgumentException e) {
				throw new DMLRuntimeException(String.format("Paramserv function: not support data partition scheme '%s'", getParam(PS_SCHEME)));
			}
		}
		return scheme;
	}

}
