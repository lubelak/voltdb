/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB Inc. are licensed under the following
 * terms and conditions:
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
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "limitexecutor.h"
#include "common/debuglog.h"
#include "common/common.h"
#include "common/tabletuple.h"
#include "plannodes/limitnode.h"
#include "storage/temptable.h"
#include "storage/tableiterator.h"

using namespace voltdb;

bool
LimitExecutor::p_init(TempTableLimits* limits)
{
    VOLT_TRACE("init limit Executor");

    LimitPlanNode* node = dynamic_cast<LimitPlanNode*>(m_abstractNode);
    assert(node);

    // Skip if we are inline
    if (node->isInline()) {
        return true;
    }

    m_state = node->getState();

    // Just copy the table schema of our input table
    setTempOutputLikeInputTable(limits);
    return true;
}

bool LimitExecutor::p_execute()
{
    assert(dynamic_cast<LimitPlanNode*>(m_abstractNode));
    TempTable* output_table = getTempOutputTable();
    assert(output_table);
    Table* input_table = getInputTable();
    assert(input_table);

    //
    // Grab the iterator for our input table, and loop through until
    // we have copy enough tuples for the limit specified by the node
    //
    TableTuple tuple(input_table->schema());
    TableIterator iterator = input_table->iteratorDeletingAsWeGo();

    int limit = -1;
    int offset = -1;
    m_state.getLimitAndOffsetByReference(m_engine, limit, offset);
    int tuple_ctr = 0;
    int tuples_skipped = 0;

    while ((limit == -1 || tuple_ctr < limit) && iterator.next(tuple)) {
        // TODO: need a way to skip / iterate N items.
        if (tuples_skipped < offset) {
            tuples_skipped++;
            continue;
        }
        tuple_ctr++;

        output_table->insertTempTuple(tuple);
    }

    return true;
}
