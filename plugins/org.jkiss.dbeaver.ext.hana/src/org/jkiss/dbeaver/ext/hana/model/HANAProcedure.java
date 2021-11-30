/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2021 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.hana.model;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.generic.model.GenericCatalog;
import org.jkiss.dbeaver.ext.generic.model.GenericFunctionResultType;
import org.jkiss.dbeaver.ext.generic.model.GenericPackage;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedure;
import org.jkiss.dbeaver.ext.generic.model.GenericProcedureParameter;
import org.jkiss.dbeaver.ext.generic.model.GenericSchema;
import org.jkiss.dbeaver.ext.generic.model.GenericStructContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCPreparedStatement;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCResultSet;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureParameterKind;
import org.jkiss.dbeaver.model.struct.rdb.DBSProcedureType;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;

public class HANAProcedure extends GenericProcedure {

    static final String DATA_TYPE_NAME_TABLE_TYPE = "TABLE_TYPE";
    static final String DATA_TYPE_NAME_ANY_TABLE_TYPE = "ANY_TABLE_TYPE";
    static final String BOOLEAN_TRUE = "TRUE";
    private static final String PARAMETER_TYPE_IN = "IN";
    private static final String PARAMETER_TYPE_INOUT = "INOUT";
    private static final String PARAMETER_TYPE_OUT = "OUT";
    private static final String PARAMETER_TYPE_RETURN = "RETURN";
    
    Map<String, List<HANAInplaceTableTypeColumn> > inplaceTableTypes;

    public HANAProcedure(GenericStructContainer container, String procedureName, String specificName,
            String description, DBSProcedureType procedureType, GenericFunctionResultType functionResultType) {
        super(container, procedureName, specificName, description, procedureType, functionResultType);
    }
    
    private void loadInplaceTableTypes(DBRProgressMonitor monitor) throws DBException {
        inplaceTableTypes = new HashMap<>();
        try (JDBCSession session = DBUtils.openMetaSession(monitor, getDataSource(), "Read procedure parameter columns")) {
            String stmt = "SELECT PARAMETER_NAME, COLUMN_NAME, DATA_TYPE_NAME, LENGTH, SCALE";
            if (DBSProcedureType.PROCEDURE == getProcedureType()) {
                stmt += " FROM SYS.PROCEDURE_PARAMETER_COLUMNS"+
                        " WHERE SCHEMA_NAME=? AND PROCEDURE_NAME=?"+
                        " ORDER BY PARAMETER_NAME, POSITION";
            } else {
                stmt += " FROM SYS.FUNCTION_PARAMETER_COLUMNS"+
                        " WHERE SCHEMA_NAME=? AND FUNCTION_NAME=?"+
                        " ORDER BY PARAMETER_NAME, POSITION";
            }
            try (JDBCPreparedStatement dbStat = session.prepareStatement(stmt)) {
                dbStat.setString(1, getParentObject().getName());
                dbStat.setString(2, getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String parameterName = dbResult.getString(1);
                        String columnName = dbResult.getString(2);
                        String typeName = dbResult.getString(3);
                        int length = dbResult.getInt(4);
                        int scale = dbResult.getInt(5);
                        
                        List<HANAInplaceTableTypeColumn> inplaceTableType = inplaceTableTypes.get(parameterName);
                        if (inplaceTableType == null) {
                            inplaceTableType = new LinkedList<HANAInplaceTableTypeColumn>();
                            inplaceTableTypes.put(parameterName, inplaceTableType);
                        }
                        inplaceTableType.add(new HANAInplaceTableTypeColumn(this, columnName, typeName, inplaceTableType.size() + 1, length, scale));
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBException(e, getDataSource());
        }
    }

    @Override
    public void loadProcedureColumns(DBRProgressMonitor monitor) throws DBException {
        try (JDBCSession session = DBUtils.openMetaSession(monitor, getDataSource(), "Read procedure parameter")) {
            String stmt;
            stmt = "SELECT PARAMETER_NAME, DATA_TYPE_NAME, LENGTH, SCALE, POSITION,"+
                   " TABLE_TYPE_SCHEMA, TABLE_TYPE_NAME, IS_INPLACE_TYPE,"+
                   " PARAMETER_TYPE, HAS_DEFAULT_VALUE";
            if (DBSProcedureType.PROCEDURE == getProcedureType()) {
                stmt += " FROM SYS.PROCEDURE_PARAMETERS"+
                        " WHERE SCHEMA_NAME=? AND PROCEDURE_NAME=?"+
                        " ORDER BY POSITION";
            } else {
                stmt += " FROM SYS.FUNCTION_PARAMETERS"+
                        " WHERE SCHEMA_NAME=? AND FUNCTION_NAME=?"+
                        " ORDER BY POSITION";
            }
            try (JDBCPreparedStatement dbStat = session.prepareStatement(stmt)) {
                dbStat.setString(1, getParentObject().getName());
                dbStat.setString(2, getName());
                try (JDBCResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String columnName = dbResult.getString(1);
                        String typeName = dbResult.getString(2);
                        int columnSize = dbResult.getInt(3);
                        int scale = dbResult.getInt(4);
                        int position = dbResult.getInt(5);
                        String parameterTypeStr = dbResult.getString(9);
                        boolean hasInplaceTableType = BOOLEAN_TRUE.equals(dbResult.getString(8));
                        boolean hasDefaultValue = BOOLEAN_TRUE.equals(dbResult.getString(10));
                        
                        DBSProcedureParameterKind parameterType;
                        if(parameterTypeStr.equals(PARAMETER_TYPE_IN)) {
                            parameterType = DBSProcedureParameterKind.IN;
                        } else if(parameterTypeStr.equals(PARAMETER_TYPE_INOUT)) {
                            parameterType = DBSProcedureParameterKind.INOUT;
                        } else if(parameterTypeStr.equals(PARAMETER_TYPE_OUT)) {
                            parameterType = DBSProcedureParameterKind.OUT;
                        } else if(parameterTypeStr.equals(PARAMETER_TYPE_RETURN)) {
                            parameterType = DBSProcedureParameterKind.RETURN;
                        } else {
                            parameterType = DBSProcedureParameterKind.UNKNOWN;
                        }     
                        DBSObject tableType = null;
                        List<HANAInplaceTableTypeColumn> inplaceTableType = null;
                        if(DATA_TYPE_NAME_TABLE_TYPE.equals(typeName)) {
                            if (hasInplaceTableType) {
                                if (inplaceTableTypes == null) {
                                    loadInplaceTableTypes(monitor);
                                }
                                inplaceTableType = inplaceTableTypes.get(columnName);
                            } else {
                                String tableTypeSchema = dbResult.getString(6);
                                String tableTypeName = dbResult.getString(7);
                                GenericSchema schema = getDataSource().getSchema(tableTypeSchema);
                                if (schema != null) {
                                    tableType = schema.getTable(monitor, tableTypeName);
                                }
                            }
                        }
                        GenericProcedureParameter column = new HANAProcedureParameter(
                                this, columnName, typeName, 0 /*valueType*/,
                                position, columnSize, scale, 0 /*precision*/, 
                                false /*notNull*/, null /*remarks*/, parameterType, 
                                tableType, inplaceTableType, hasDefaultValue);
                        addColumn(column);
                    }
                }
            }
        } catch (SQLException e) {
            throw new DBException(e, getDataSource());
        }
    }

    @Association
    public List<HANADependency> getDependencies(DBRProgressMonitor monitor) throws DBException {
        return HANADependency.readDependencies(monitor, this);
    }

    @Property(hidden = true)
    public GenericCatalog getCatalog() { return super.getCatalog(); }

    @Property(hidden = true)
    public GenericPackage getPackage() { return super.getPackage(); }
    
    // hide, as not properly filled by driver. type is anyway obvious from RETURN parameter
    @Property(hidden = true)
    public GenericFunctionResultType getFunctionResultType() { return super.getFunctionResultType(); }

}
