/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.testing.story;

public class TestThresholdsReconExecuteMultithreaded extends TestThresholdsReconExecute {

    @Override
    protected int getWorkerThreads() {
        return 2;
    }
}
