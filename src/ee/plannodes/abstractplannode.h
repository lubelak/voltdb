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

#ifndef HSTOREPLANNODE_H
#define HSTOREPLANNODE_H

#include "SchemaColumn.h"

#include "catalog/database.h"
#include "common/ids.h"
#include "common/types.h"
#include "common/PlannerDomValue.h"

#include <map>
#include <string>
#include <vector>

namespace voltdb {

class AbstractExecutor;
class AbstractExpression;
class Table;
class TupleSchema;

class AbstractPlanNode
{
public:
    virtual ~AbstractPlanNode();

    // ------------------------------------------------------------------
    // CHILDREN + PARENTS METHODS
    // ------------------------------------------------------------------
    void addChild(AbstractPlanNode* child);
    std::vector<AbstractPlanNode*>& getChildren();
    std::vector<int32_t>& getChildIds();
    const std::vector<AbstractPlanNode*>& getChildren() const;

    // ------------------------------------------------------------------
    // INLINE PLANNODE METHODS
    // ------------------------------------------------------------------
    void addInlinePlanNode(AbstractPlanNode* inline_node);
    AbstractPlanNode* getInlinePlanNode(PlanNodeType type) const;
    std::map<PlanNodeType, AbstractPlanNode*>& getInlinePlanNodes();
    const std::map<PlanNodeType, AbstractPlanNode*>& getInlinePlanNodes() const;
    bool isInline() const;

    // ------------------------------------------------------------------
    // DATA MEMBER METHODS
    // ------------------------------------------------------------------
    int32_t getPlanNodeId() const;
    void setPlanNodeIdForTest(int32_t id) { m_planNodeId = id; }

    // currently a hack needed to initialize the executors.
    CatalogId databaseId() const { return 1; }

    void setExecutor(AbstractExecutor* executor);
    inline AbstractExecutor* getExecutor() const { return m_executor; }

    //
    // Each sub-class will have to implement this function to return their type
    // This is better than having to store redundant types in all the objects
    //
    virtual PlanNodeType getPlanNodeType() const = 0;

    /**
     * Get the output columns that make up the output schema for
     * this plan node.  The column order is implicit in their
     * order in this vector.
     */
    const std::vector<SchemaColumn*>& getOutputSchema() const;

    /**
     * Get the output number of columns -- strictly for use with plannode
     * classes that "project" a new output schema (vs. passing one up from a child).
     * This is cleaner than using "getOutputSchema().size()" in such cases, such as Projection nodes,
     * when m_outputSchema and m_validOutputColumnCount are known to be valid and in agreement.
     */
    int getValidOutputColumnCount() const
    {
        // Assert that this plan node defined (derialized in) its own output schema.
        assert(m_validOutputColumnCount >= 0);
        return m_validOutputColumnCount;
    }

    /**
     * Convenience method:
     * Generate a TupleSchema based on the contents of the output schema
     * from the plan
     *
     * @param allowNulls whether or not the generated schema should
     * permit null values in the output columns.
     *TODO: -- This is always passed true, so deprecate it?
     */
    TupleSchema* generateTupleSchema(bool allowNulls=true) const;

    /**
     * Convenience method:
     * Generate a TupleSchema based on the expected format for DML results.
     */
    static TupleSchema* generateDMLCountTupleSchema();

    // ------------------------------------------------------------------
    // UTILITY METHODS
    // ------------------------------------------------------------------
    static AbstractPlanNode* fromJSONObject(PlannerDomValue obj);

    // Debugging convenience methods
    std::string debug() const;
    std::string debug(bool traverse) const;
    std::string debug(const std::string& spacer) const;
    virtual std::string debugInfo(const std::string& spacer) const = 0;

protected:
    virtual void loadFromJSONObject(PlannerDomValue obj) = 0;

    AbstractPlanNode()
        : m_planNodeId(-1)
        , m_executor(NULL)
        , m_isInline(false)
    { }

    static AbstractExpression* loadExpressionFromJSONObject(const char* label,
                                                            const PlannerDomValue& obj);
    static void loadExpressionsFromJSONObject(std::vector<AbstractExpression*>& arrayOut,
                                              const char* label,
                                              const PlannerDomValue& obj);



    //
    // Every PlanNode will have a unique id assigned to it at compile time
    //
    int32_t m_planNodeId;
    //
    // A node can have multiple children
    //
    std::vector<AbstractPlanNode*> m_children;
    std::vector<int32_t> m_childIds;
    //
    // We also keep a pointer to this node's executor so that we can
    // reference it quickly
    // at runtime without having to look-up a map
    //
    AbstractExecutor* m_executor; // volatile
    //
    // Some Executors can take advantage of multiple internal PlanNodes
    // to perform tasks inline. This can be a big speed increase
    //
    std::map<PlanNodeType, AbstractPlanNode*> m_inlineNodes;
    bool m_isInline;

private:
    static const int SCHEMA_UNDEFINED_SO_GET_FROM_INLINE_PROJECTION = -1;
    static const int SCHEMA_UNDEFINED_SO_GET_FROM_CHILD = -2;

    // This is mostly used to hold one of the SCHEMA_UNDEFINED_SO_GET_FROM_ flags
    // or some/any non-negative value indicating that m_outputSchema is valid.
    // the fact that it also matches the size of m_outputSchema -- when it is valid
    // -- MIGHT come in handy?
    int m_validOutputColumnCount;
    std::vector<SchemaColumn*> m_outputSchema;
};

}

#endif
