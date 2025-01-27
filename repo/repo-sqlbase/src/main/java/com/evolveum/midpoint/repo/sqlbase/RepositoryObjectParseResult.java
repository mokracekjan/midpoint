/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqlbase;

import com.evolveum.midpoint.prism.ParsingContext;

/** Result for deserialization of prism object stored in the repository. */
public class RepositoryObjectParseResult<T> {

    public final ParsingContext parsingContext;
    public final T prismObject;

    public RepositoryObjectParseResult(ParsingContext parsingContext, T schemaObject) {
        this.parsingContext = parsingContext;
        this.prismObject = schemaObject;
    }
}
