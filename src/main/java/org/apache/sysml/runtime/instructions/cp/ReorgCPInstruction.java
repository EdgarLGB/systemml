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

import org.apache.sysml.parser.Expression.DataType;
import org.apache.sysml.parser.Expression.ValueType;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.functionobjects.DiagIndex;
import org.apache.sysml.runtime.functionobjects.RevIndex;
import org.apache.sysml.runtime.functionobjects.SortIndex;
import org.apache.sysml.runtime.functionobjects.SwapIndex;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.matrix.operators.ReorgOperator;
import org.apache.sysml.runtime.util.DataConverter;

public class ReorgCPInstruction extends UnaryCPInstruction {
	// sort-specific attributes (to enable variable attributes)
	private final CPOperand _col;
	private final CPOperand _desc;
	private final CPOperand _ixret;

	/**
	 * for opcodes r' and rdiag
	 * 
	 * @param op
	 *            operator
	 * @param in
	 *            cp input operand
	 * @param out
	 *            cp output operand
	 * @param opcode
	 *            the opcode
	 * @param istr
	 *            ?
	 */
	private ReorgCPInstruction(Operator op, CPOperand in, CPOperand out, String opcode, String istr) {
		this(op, in, out, null, null, null, opcode, istr);
	}

	/**
	 * for opcode rsort
	 * 
	 * @param op
	 *            operator
	 * @param in
	 *            cp input operand
	 * @param col
	 *            ?
	 * @param desc
	 *            ?
	 * @param ixret
	 *            ?
	 * @param out
	 *            cp output operand
	 * @param opcode
	 *            the opcode
	 * @param istr
	 *            ?
	 */
	private ReorgCPInstruction(Operator op, CPOperand in, CPOperand out, CPOperand col, CPOperand desc, CPOperand ixret,
			String opcode, String istr) {
		super(CPType.Reorg, op, in, out, opcode, istr);
		_col = col;
		_desc = desc;
		_ixret = ixret;
	}

	public static ReorgCPInstruction parseInstruction ( String str ) {
		CPOperand in = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		CPOperand out = new CPOperand("", ValueType.UNKNOWN, DataType.UNKNOWN);
		
		String[] parts = InstructionUtils.getInstructionPartsWithValueType(str);
		String opcode = parts[0];
		
		if ( opcode.equalsIgnoreCase("r'") ) {
			InstructionUtils.checkNumFields(str, 2, 3);
			in.split(parts[1]);
			out.split(parts[2]);
			int k = Integer.parseInt(parts[3]);
			return new ReorgCPInstruction(new ReorgOperator(SwapIndex.getSwapIndexFnObject(), k), in, out, opcode, str);
		} 
		else if ( opcode.equalsIgnoreCase("rev") ) {
			parseUnaryInstruction(str, in, out); //max 2 operands
			return new ReorgCPInstruction(new ReorgOperator(RevIndex.getRevIndexFnObject()), in, out, opcode, str);
		}
		else if ( opcode.equalsIgnoreCase("rdiag") ) {
			parseUnaryInstruction(str, in, out); //max 2 operands
			return new ReorgCPInstruction(new ReorgOperator(DiagIndex.getDiagIndexFnObject()), in, out, opcode, str);
		} 
		else if ( opcode.equalsIgnoreCase("rsort") ) {
			InstructionUtils.checkNumFields(parts, 5);
			in.split(parts[1]);
			out.split(parts[5]);
			CPOperand col = new CPOperand(parts[2]);
			CPOperand desc = new CPOperand(parts[3]);
			CPOperand ixret = new CPOperand(parts[4]);
			return new ReorgCPInstruction(new ReorgOperator(new SortIndex(1,false,false)), 
				in, out, col, desc, ixret, opcode, str);
		}
		else {
			throw new DMLRuntimeException("Unknown opcode while parsing a ReorgInstruction: " + str);
		}
	}
	
	@Override
	public void processInstruction(ExecutionContext ec) {
		//acquire inputs
		MatrixBlock matBlock = ec.getMatrixInput(input1.getName(), getExtendedOpcode());
		ReorgOperator r_op = (ReorgOperator) _optr;
		if( r_op.fn instanceof SortIndex ) {
			//additional attributes for sort
			int[] cols = _col.getDataType().isMatrix() ? DataConverter.convertToIntVector(ec.getMatrixInput(_col.getName())) :
				new int[]{(int)ec.getScalarInput(_col.getName(), _col.getValueType(), _col.isLiteral()).getLongValue()};
			boolean desc = ec.getScalarInput(_desc.getName(), _desc.getValueType(), _desc.isLiteral()).getBooleanValue();
			boolean ixret = ec.getScalarInput(_ixret.getName(), _ixret.getValueType(), _ixret.isLiteral()).getBooleanValue();
			r_op = r_op.setFn(new SortIndex(cols, desc, ixret));
		}
		
		//execute operation
		MatrixBlock soresBlock = (MatrixBlock) (matBlock.reorgOperations(r_op, new MatrixBlock(), 0, 0, 0));
		
		//release inputs/outputs
		if( r_op.fn instanceof SortIndex && _col.getDataType().isMatrix() )
			ec.releaseMatrixInput(_col.getName());
		ec.releaseMatrixInput(input1.getName(), getExtendedOpcode());
		ec.setMatrixOutput(output.getName(), soresBlock, getExtendedOpcode());
	}
}
