/*
 * Copyright (c) 2010-2013 Evolveum
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
package com.evolveum.midpoint.repo.sql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
/**
 * @author skublik
 */
public abstract class ConverterSqlAndJavaObject {

	public abstract <T> T convertToValue(ResultSet rs, String nameOfColumn, Class<T> javaClazz) throws SQLException;
	
	public abstract <T> T convertToValue(ResultSet rs, int index, Class<T> javaClazz) throws SQLException;
	
	public abstract Types getSqlType(Object value);
	
}