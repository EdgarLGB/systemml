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

package org.apache.sysml.runtime.instructions.spark;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

import org.apache.sysml.hops.AggBinaryOp.SparkAggType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.functionobjects.Multiply;
import org.apache.sysml.runtime.functionobjects.Plus;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.spark.functions.FilterNonEmptyBlocksFunction;
import org.apache.sysml.runtime.instructions.spark.utils.RDDAggregateUtils;
import org.apache.sysml.runtime.instructions.spark.utils.SparkUtils;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.mapred.IndexedMatrixValue;
import org.apache.sysml.runtime.matrix.operators.AggregateBinaryOperator;
import org.apache.sysml.runtime.matrix.operators.AggregateOperator;
import org.apache.sysml.runtime.matrix.operators.Operator;

/**
 * Cpmm: cross-product matrix multiplication operation (distributed matrix multiply
 * by join over common dimension and subsequent aggregation of partial results).
 * 
 * NOTE: There is additional optimization potential by preventing aggregation for a single
 * block on the common dimension. However, in such a case we would never pick cpmm because
 * this would result in a degree of parallelism of 1.
 * 
 */
public class CpmmSPInstruction extends BinarySPInstruction {
	private SparkAggType _aggtype;

	private CpmmSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, SparkAggType aggtype, String opcode, String istr) {
		super(SPType.CPMM, op, in1, in2, out, opcode, istr);
		_aggtype = aggtype;
	}

	public static CpmmSPInstruction parseInstruction( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		if ( !opcode.equalsIgnoreCase("cpmm"))
			throw new DMLRuntimeException("CpmmSPInstruction.parseInstruction(): Unknown opcode " + opcode);
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand out = new CPOperand(parts[3]);
		AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
		AggregateBinaryOperator aggbin = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
		SparkAggType aggtype = SparkAggType.valueOf(parts[4]);
		return new CpmmSPInstruction(aggbin, in1, in2, out, aggtype, opcode, str);
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		
		//get rdd inputs
		JavaPairRDD<MatrixIndexes,MatrixBlock> in1 = sec.getBinaryBlockRDDHandleForVariable(input1.getName());
		JavaPairRDD<MatrixIndexes,MatrixBlock> in2 = sec.getBinaryBlockRDDHandleForVariable(input2.getName());
		MatrixCharacteristics mc1 = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mc2 = sec.getMatrixCharacteristics(input2.getName());
		
		if( _aggtype == SparkAggType.SINGLE_BLOCK ) {
			//prune empty blocks of ultra-sparse matrices
			in1 = in1.filter(new FilterNonEmptyBlocksFunction());
			in2 = in2.filter(new FilterNonEmptyBlocksFunction());
		}
		
		//compute preferred join degree of parallelism
		int numPreferred = getPreferredParJoin(mc1, mc2, in1.getNumPartitions(), in2.getNumPartitions());
		int numPartJoin = Math.min(getMaxParJoin(mc1, mc2), numPreferred);
		
		//process core cpmm matrix multiply 
		JavaPairRDD<Long, IndexedMatrixValue> tmp1 = in1.mapToPair(new CpmmIndexFunction(true));
		JavaPairRDD<Long, IndexedMatrixValue> tmp2 = in2.mapToPair(new CpmmIndexFunction(false));
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = tmp1
			.join(tmp2, numPartJoin)                // join over common dimension
			.mapToPair(new CpmmMultiplyFunction()); // compute block multiplications
		
		//process cpmm aggregation and handle outputs
		if( _aggtype == SparkAggType.SINGLE_BLOCK ) {
			//prune empty blocks and aggregate all results
			out = out.filter(new FilterNonEmptyBlocksFunction());
			MatrixBlock out2 = RDDAggregateUtils.sumStable(out);
			
			//put output block into symbol table (no lineage because single block)
			//this also includes implicit maintenance of matrix characteristics
			sec.setMatrixOutput(output.getName(), out2, getExtendedOpcode());
		}
		else { //DEFAULT: MULTI_BLOCK
			out = RDDAggregateUtils.sumByKeyStable(out, false);
			
			//put output RDD handle into symbol table
			sec.setRDDHandleForVariable(output.getName(), out);
			sec.addLineageRDD(output.getName(), input1.getName());
			sec.addLineageRDD(output.getName(), input2.getName());
			
			//update output statistics if not inferred
			updateBinaryMMOutputMatrixCharacteristics(sec, true);
		}
	}
	
	private static int getPreferredParJoin(MatrixCharacteristics mc1, MatrixCharacteristics mc2, int numPar1, int numPar2) {
		int defPar = SparkExecutionContext.getDefaultParallelism(true);
		int maxParIn = Math.max(numPar1, numPar2);
		int maxSizeIn = SparkUtils.getNumPreferredPartitions(mc1) +
			SparkUtils.getNumPreferredPartitions(mc2);
		int tmp = (mc1.dimsKnown(true) && mc2.dimsKnown(true)) ? 
			Math.max(maxSizeIn, maxParIn) : maxParIn;
		return (tmp > defPar/2) ? Math.max(tmp, defPar) : tmp;
	}
	
	private static int getMaxParJoin(MatrixCharacteristics mc1, MatrixCharacteristics mc2) {
		return mc1.colsKnown() ? (int)mc1.getNumColBlocks() :
			mc2.rowsKnown() ? (int)mc2.getNumRowBlocks() :
			Integer.MAX_VALUE;
	}

	private static class CpmmIndexFunction implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, Long, IndexedMatrixValue>
	{
		private static final long serialVersionUID = -1187183128301671162L;
		private final boolean _left;
		
		public CpmmIndexFunction( boolean left ) {
			_left = left;
		}
		
		@Override
		public Tuple2<Long, IndexedMatrixValue> call(Tuple2<MatrixIndexes, MatrixBlock> arg0) throws Exception {
			IndexedMatrixValue value = new IndexedMatrixValue(arg0._1(), arg0._2());
			Long key = _left ? arg0._1.getColumnIndex() : arg0._1.getRowIndex();
			return new Tuple2<>(key, value);
		}
	}

	private static class CpmmMultiplyFunction implements PairFunction<Tuple2<Long, Tuple2<IndexedMatrixValue,IndexedMatrixValue>>, MatrixIndexes, MatrixBlock>
	{
		private static final long serialVersionUID = -2009255629093036642L;
		private AggregateBinaryOperator _op = null;

		@Override
		public Tuple2<MatrixIndexes, MatrixBlock> call(Tuple2<Long, Tuple2<IndexedMatrixValue, IndexedMatrixValue>> arg0)
			throws Exception
		{
			if( _op == null ) { //lazy operator construction
				AggregateOperator agg = new AggregateOperator(0, Plus.getPlusFnObject());
				_op = new AggregateBinaryOperator(Multiply.getMultiplyFnObject(), agg);
			}
			
			MatrixBlock blkIn1 = (MatrixBlock)arg0._2()._1().getValue();
			MatrixBlock blkIn2 = (MatrixBlock)arg0._2()._2().getValue();
			MatrixIndexes ixOut = new MatrixIndexes();
			MatrixBlock blkOut = new MatrixBlock();
			
			//core block matrix multiplication 
			blkIn1.aggregateBinaryOperations(blkIn1, blkIn2, blkOut, _op);
			
			//return target block
			ixOut.setIndexes(arg0._2()._1().getIndexes().getRowIndex(),
				arg0._2()._2().getIndexes().getColumnIndex());
			return new Tuple2<>( ixOut, blkOut );
		}
	}
}
