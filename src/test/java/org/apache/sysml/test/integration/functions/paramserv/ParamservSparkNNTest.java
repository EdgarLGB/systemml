package org.apache.sysml.test.integration.functions.paramserv;

import org.apache.sysml.api.DMLException;
import org.apache.sysml.api.DMLScript;
import org.apache.sysml.parser.Statement;
import org.apache.sysml.test.integration.AutomatedTestBase;
import org.apache.sysml.test.integration.TestConfiguration;
import org.junit.Test;

public class ParamservSparkNNTest extends AutomatedTestBase {

	private static final String TEST_NAME1 = "paramserv-test";
	private static final String TEST_NAME2 = "paramserv-spark-worker-failed";
	private static final String TEST_NAME3 = "paramserv-spark-agg-service-failed";

	private static final String TEST_DIR = "functions/paramserv/";
	private static final String TEST_CLASS_DIR = TEST_DIR + ParamservSparkNNTest.class.getSimpleName() + "/";

	@Override
	public void setUp() {
		addTestConfiguration(TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] {}));
		addTestConfiguration(TEST_NAME2, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME2, new String[] {}));
		addTestConfiguration(TEST_NAME3, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME3, new String[] {}));
	}

	@Test
	public void testParamservBSPBatchDisjointContiguous() {
		runDMLTest(1, 3, Statement.PSUpdateType.BSP, Statement.PSFrequency.BATCH, 16, Statement.PSScheme.DISJOINT_CONTIGUOUS);
	}

	@Test
	public void testParamservASPBatchDisjointContiguous() {
		// FIXME When setting 'epochs' to 10, arbitary error will occur. Dimensions mismatch matrix-matrix binary operations: [0x0 vs 1x512]
		runDMLTest(1, 3, Statement.PSUpdateType.ASP, Statement.PSFrequency.BATCH, 16, Statement.PSScheme.DISJOINT_CONTIGUOUS);
	}

	@Test
	public void testParamservBSPEpochDisjointContiguous() {
		// FIXME When setting 'epochs' to 10, arbitary error will occur. Caused by: (SparseBlockCSR.java:467) java.lang.ArrayIndexOutOfBoundsException: 118
		runDMLTest(1, 3, Statement.PSUpdateType.BSP, Statement.PSFrequency.EPOCH, 16, Statement.PSScheme.DISJOINT_CONTIGUOUS);
	}

	@Test
	public void testParamservASPEpochDisjointContiguous() {
		// FIXME When setting 'epochs' to 10, arbitary error will occur. Caused by: (SparseBlockCSR.java:467) java.lang.ArrayIndexOutOfBoundsException: 113
		runDMLTest(1, 3, Statement.PSUpdateType.ASP, Statement.PSFrequency.EPOCH, 16, Statement.PSScheme.DISJOINT_CONTIGUOUS);
	}

	@Test
	public void testParamservWorkerFailed() {
		runDMLTest(TEST_NAME2, true, DMLException.class, "Invalid indexing by name in unnamed list: worker_err.");
	}

	@Test
	public void testParamservAggServiceFailed() {
		runDMLTest(TEST_NAME3, true, DMLException.class, "Invalid indexing by name in unnamed list: agg_service_err.");
	}

	private void runDMLTest(String testname, boolean exceptionExpected, Class<?> expectedException, String errMessage) {
		programArgs = new String[] { "-explain" };
		internalRunDMLTest(testname, exceptionExpected, expectedException, errMessage);
	}

	private void runDMLTest(int epochs, int workers, Statement.PSUpdateType utype, Statement.PSFrequency freq, int batchsize, Statement.PSScheme scheme) {
		programArgs = new String[] { "-explain", "-nvargs", "mode=REMOTE_SPARK", "epochs=" + epochs,
				"workers=" + workers, "utype=" + utype, "freq=" + freq, "batchsize=" + batchsize,
				"scheme=" + scheme };
		internalRunDMLTest(ParamservSparkNNTest.TEST_NAME1, false, null, null);
	}

	private void internalRunDMLTest(String testname, boolean exceptionExpected, Class<?> expectedException,
			String errMessage) {
		DMLScript.RUNTIME_PLATFORM oldRtplatform = AutomatedTestBase.rtplatform;
		boolean oldUseLocalSparkConfig = DMLScript.USE_LOCAL_SPARK_CONFIG;
		AutomatedTestBase.rtplatform = DMLScript.RUNTIME_PLATFORM.SPARK;
		DMLScript.USE_LOCAL_SPARK_CONFIG = true;

		try {
			TestConfiguration config = getTestConfiguration(testname);
			loadTestConfiguration(config);
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + testname + ".dml";
			// The test is not already finished, so it is normal to have the NPE
			runTest(true, exceptionExpected, expectedException, errMessage, -1);
		} finally {
			AutomatedTestBase.rtplatform = oldRtplatform;
			DMLScript.USE_LOCAL_SPARK_CONFIG = oldUseLocalSparkConfig;
		}
	}
}
