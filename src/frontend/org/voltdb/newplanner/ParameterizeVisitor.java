/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.newplanner;

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.util.SqlBasicVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Visitor that can replace all the {@link SqlLiteral} to {@link SqlDynamicParam} inplace.
 * It is used for parameterizing queries.
 *
 * @author Chao Zhou
 * @since 8.4
 */
public class ParameterizeVisitor extends SqlBasicVisitor<SqlNode> {
    private final List<SqlLiteral> sqlLiteralList = new ArrayList<>();
    private int dynamicParamIndex = 0;

    private void setNodeOperand(SqlCall sqlCall, int i, SqlNode operand) {
//        try {
//            sqlCall.setOperand(i, operand);
//        } catch (UnsupportedOperationException e){
//            if (sqlCall instanceof SqlOrderBy) {
//                switch (i) {
//                    case 0:
//                        ((SqlOrderBy) sqlCall).query = Objects.requireNonNull((SqlNodeList) operand);
//                        break;
//                    case 1:
//                        selectList = (SqlNodeList) operand;
//                        break;
//                    case 2:
//                        from = operand;
//                        break;
//                    case 3:
//                        where = operand;
//                        break;
//                    default:
//                        throw new AssertionError(i);
//                }
//            }
//        }
    }

    public List<SqlLiteral> getSqlLiteralList() {
        return sqlLiteralList;
    }

    public SqlNode visit(SqlLiteral literal) {
        sqlLiteralList.add(literal);

        return new SqlDynamicParam(dynamicParamIndex++, literal.getParserPosition());
    }

    public SqlNode visit(SqlDynamicParam param) {
        return new SqlDynamicParam(dynamicParamIndex++, param.getParserPosition());
    }

    public SqlNode visit(SqlCall call) {
        List<SqlNode> operandList = call.getOperandList();
        for (int i = 0; i < operandList.size(); i++) {
            SqlNode operand = operandList.get(i);
            if (operand == null) {
                break;
            }
            SqlNode visitResult = operand.accept(this);
            if (operand instanceof SqlLiteral || operand instanceof SqlDynamicParam) {
                call.setOperand(i, visitResult);
            }
        }
        return null;
    }
}
