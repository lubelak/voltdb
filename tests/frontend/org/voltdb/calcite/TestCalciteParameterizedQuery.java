/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
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
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.calcite;

import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.util.Litmus;
import org.apache.calcite.util.NlsString;
import org.hsqldb_voltpatches.HSQLInterface;
import org.junit.Before;
import org.junit.Test;
import org.voltcore.messaging.HostMessenger;
import org.voltdb.CatalogContext;
import org.voltdb.catalog.Catalog;
import org.voltdb.compiler.*;
import org.voltdb.newplanner.ParameterizeVisitor;
import org.voltdb.parser.ParserFactory;
import org.voltdb.planner.ParameterizationInfo;
import org.voltdb.planner.QueryPlanner;
import org.voltdb.planner.StatementPartitioning;
import org.voltdb.planner.TrivialCostModel;
import org.voltdb.settings.ClusterSettings;
import org.voltdb.settings.DbSettings;
import org.voltdb.settings.NodeSettings;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.CompressionService;
import org.voltdb.utils.Encoder;
import org.voltdb.utils.MiscUtils;

import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;


public class TestCalciteParameterizedQuery {
    private CatalogContext m_context;
    private QueryPlanner m_planner;
    private HSQLInterface m_hsql;

    @Before
    public void setUp() throws Exception {
        VoltProjectBuilder builder = new VoltProjectBuilder();
        builder.addLiteralSchema("create table t(id bigint not null, name varchar(100), cnt int, primary key(id));");
        builder.addPartitionInfo("t", "id");

        final File jar = new File("testcalciteParameterizedQuery.jar");
        jar.deleteOnExit();
        builder.compile("testcalciteParameterizedQuery.jar");
        byte[] bytes = MiscUtils.fileToBytes(new File("testcalciteParameterizedQuery.jar"));
        String serializedCatalog = CatalogUtil.getSerializedCatalogStringFromJar(CatalogUtil.loadAndUpgradeCatalogFromJar(bytes, false).getFirst());
        assertNotNull(serializedCatalog);
        Catalog c = new Catalog();
        c.execute(serializedCatalog);
        DbSettings settings = new DbSettings(ClusterSettings.create().asSupplier(), NodeSettings.create());
        m_context = new CatalogContext(c, settings, 0, 0, bytes, null, new byte[] {}, mock(HostMessenger.class));

        m_hsql = HSQLInterface.loadHsqldb(ParameterizationInfo.getParamStateManager());

        String binDDL = m_context.database.getSchema();
        String ddl = CompressionService.decodeBase64AndDecompress(binDDL);
        String[] commands = ddl.split("\n");
        for (String command : commands) {
            String decoded_cmd = Encoder.hexDecodeToString(command);
            decoded_cmd = decoded_cmd.trim();
            if (decoded_cmd.length() == 0)
                continue;
            try {
                m_hsql.runDDLCommand(decoded_cmd);
            }
            catch (HSQLInterface.HSQLParseException e) {
                // need a good error message here
                throw new RuntimeException("Error creating hsql: " + e.getMessage() + " in DDL statement: " + decoded_cmd);
            }
        }
    }

    @Test
    public void testParameterizedVisitor() throws SqlParseException {
        String sql = "select * from T where id = 7 and name = 'Chao' and cnt = 566 LIMIT 2 OFFSET 3";
        SqlParser parser = ParserFactory.create(sql);
        SqlNode sqlNode = parser.parseStmt();
        ParameterizeVisitor visitor = new ParameterizeVisitor();
        sqlNode.accept(visitor);

        sql = "select * from T where id = ? and name = ? and cnt = ? LIMIT ? OFFSET ?";
        parser = ParserFactory.create(sql);

        SqlNode sqlkkkk = parser.parseStmt();
        assertTrue(SqlNode.equalDeep(sqlkkkk, sqlNode, Litmus.THROW));

        String[] queries = {"select * from T where id = 7 and name = 'Chao' and cnt = 566 LIMIT 2 OFFSET 3",
                "select * from T where id = 7 and cnt < 566 and name = 'Chao'",
                "INSERT INTO T VALUES (7, 'Chao', 566)"
        };

        String[] parameterizedQueries = {"select * from T where id = ? and name = ? and cnt = ? LIMIT ? OFFSET ?",
                "select * from T where id = ? and cnt < ? and name = ?",
                "INSERT INTO T VALUES (?, ?, ?)"
        };

        List<Object>[] values= new List[]{Arrays.asList(BigDecimal.valueOf(7), new NlsString("Chao", null, null), BigDecimal.valueOf(566), BigDecimal.valueOf(2), BigDecimal.valueOf(3)),
                Arrays.asList(BigDecimal.valueOf(7), BigDecimal.valueOf(566), new NlsString("Chao", null, null)),
                Arrays.asList(BigDecimal.valueOf(7), new NlsString("Chao", null, null), BigDecimal.valueOf(566))};

        for(int i=0; i<queries.length; i++){
            SqlParser actualParser = ParserFactory.create(queries[i]);
            SqlNode actualSqlNode = actualParser.parseStmt();
            ParameterizeVisitor actualVisitor = new ParameterizeVisitor();
            actualSqlNode.accept(actualVisitor);

            SqlParser expectedParser = ParserFactory.create(parameterizedQueries[i]);
            SqlNode expectedSqlNode = expectedParser.parseStmt();
            assertTrue(SqlNode.equalDeep(actualSqlNode, expectedSqlNode, Litmus.THROW));

            assertValuesEqual(values[i], actualVisitor.getSqlLiteralList());
        }
    }

    private void assertValuesEqual(List<Object> expectedValues, List<SqlLiteral> actualValues) {
        assertEquals(expectedValues.size(), actualValues.size());
        for (int i=0; i<expectedValues.size(); i++){
            assertEquals(expectedValues.get(i), actualValues.get(i).getValue());
        }
    }

    @Test
    public void testParameterizedQuery() throws SqlParseException {
//        String sql = "select * from T where id = 7 and name = 'aaa' and cnt = 566 LIMIT 2 OFFSET 3";
//        SqlParser parser = ParserFactory.create(sql);
//        SqlNode sqlNode = parser.parseStmt();
//        System.out.println(sqlNode.toString());
//        assertEquals(1,1);
//
//        ParameterizeVisitor visitor = new ParameterizeVisitor();
//        sqlNode.accept(visitor);
//
//        String parsedToken;
//
//        m_planner = new QueryPlanner(
//                sql,
//                "PlannerTool",
//                "PlannerToolProc",
//                m_context.database,
//                StatementPartitioning.inferPartitioning(),
//                m_hsql,
//                new DatabaseEstimates(),
//                !VoltCompiler.DEBUG_MODE,
//                new TrivialCostModel(),
//                null,
//                null,
//                DeterminismMode.FASTER,
//                false);
//
//        m_planner.parse();
//        parsedToken = m_planner.parameterize();
//
//        m_planner = new QueryPlanner(
//                sql,
//                "PlannerTool",
//                "PlannerToolProc",
//                m_context.database,
//                StatementPartitioning.inferPartitioning(),
//                m_hsql,
//                new DatabaseEstimates(),
//                !VoltCompiler.DEBUG_MODE,
//                new TrivialCostModel(),
//                null,
//                null,
//                DeterminismMode.FASTER,
//                false);
//        m_planner.parse();
//        assertEquals(m_planner.parameterize(), parsedToken);
//        assertFalse(parsedToken.equals(m_planner.getXmlSQL().toMinString()));
//
//        int a=111;
    }
}
