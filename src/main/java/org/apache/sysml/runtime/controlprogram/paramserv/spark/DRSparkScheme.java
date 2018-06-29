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

package org.apache.sysml.runtime.controlprogram.paramserv.spark;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.sysml.runtime.controlprogram.paramserv.ParamservUtils;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;

/**
 * Data partitioner Disjoint_Random:
 * for each worker, use a permutation multiply P[beg:end,] %*% X,
 * where P is constructed for example with P=table(seq(1,nrow(X)),sample(nrow(X), nrow(X))),
 * i.e., sampling without replacement to ensure disjointness.
 */
public class DRSparkScheme extends DataPartitionSparkScheme {

	protected DRSparkScheme() {
		// No-args constructor used for deserialization
	}

	private List<MatrixBlock> internalDoPartitioning(int k, MatrixBlock mb, MatrixBlock permutation) {
		int batchSize = (int) Math.ceil((double) mb.getNumRows() / k);
		return IntStream.range(0, k).mapToObj(i -> {
			int begin = i * batchSize;
			int end = Math.min((i + 1) * batchSize, mb.getNumRows());
			MatrixBlock slicedPerm = permutation.slice(begin, end - 1);
			return slicedPerm.aggregateBinaryOperations(slicedPerm, mb, new MatrixBlock(),
					InstructionUtils.getMatMultOperator(k));
		}).collect(Collectors.toList());
	}

	@Override
	public Result doPartitioning(int workersNum, MatrixBlock features, MatrixBlock labels) {
		// Generate a single permutation matrix (workers use slices)
		MatrixBlock permutation = ParamservUtils.generatePermutation((int) features.getNumRows());
		List<MatrixBlock> pfs = internalDoPartitioning(workersNum, features, permutation);
		List<MatrixBlock> pls = internalDoPartitioning(workersNum, labels, permutation);
		return new Result(pfs, pls);
	}
}
