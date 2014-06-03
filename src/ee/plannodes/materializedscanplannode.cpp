/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
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

#include "materializedscanplannode.h"

#include "expressions/abstractexpression.h"

#include <sstream>

namespace voltdb {

PlanNodeType
MaterializedScanPlanNode::getPlanNodeType() const { return PLAN_NODE_TYPE_MATERIALIZEDSCAN; }

MaterializedScanPlanNode::~MaterializedScanPlanNode()
{
    delete m_tableRowsExpression;
}

std::string MaterializedScanPlanNode::debugInfo(const std::string &spacer) const
{
    std::ostringstream buffer;
    buffer << spacer << "MATERERIALIZED SCAN Expression: <NULL>";
    return (buffer.str());
}

void MaterializedScanPlanNode::loadFromJSONObject(PlannerDomValue obj)
{
    m_tableRowsExpression = loadExpressionFromJSONObject("TABLE_DATA", obj);
    assert(m_tableRowsExpression);

    if (obj.hasNonNullKey("SORT_DIRECTION")) {
        std::string sortDirectionString = obj.valueForKey("SORT_DIRECTION").asStr();
        m_sortDirection = stringToSortDirection(sortDirectionString);
    }
}

}
