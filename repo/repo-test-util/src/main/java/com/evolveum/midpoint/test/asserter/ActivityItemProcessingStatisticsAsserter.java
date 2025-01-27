/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.test.asserter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.AssertJUnit.assertEquals;

import com.evolveum.midpoint.schema.statistics.IterationInformation;
import com.evolveum.midpoint.schema.statistics.OutcomeKeyedCounterTypeUtil;
import com.evolveum.midpoint.schema.util.task.ActivityItemProcessingStatisticsUtil;
import com.evolveum.midpoint.test.IntegrationTestTools;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ActivityItemProcessingStatisticsType;

/**
 *  Asserter that checks iterative task information.
 */
@SuppressWarnings("WeakerAccess")
public class ActivityItemProcessingStatisticsAsserter<RA> extends AbstractAsserter<RA> {

    private final ActivityItemProcessingStatisticsType information;

    ActivityItemProcessingStatisticsAsserter(ActivityItemProcessingStatisticsType information, RA returnAsserter, String details) {
        super(returnAsserter, details);
        this.information = information;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertExecutions(int expected) {
        assertThat(information.getExecution().size()).as("# of executions").isEqualTo(expected);
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertTotalCounts(int nonFailure, int failure) {
        assertNonFailureCount(nonFailure);
        assertFailureCount(failure);
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertTotalCounts(int success, int failure, int skip) {
        assertSuccessCount(success);
        assertFailureCount(failure);
        assertSkipCount(skip);
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertNonFailureCount(int success) {
        assertEquals("Wrong value of total 'non-failure' counter", success, getNonFailureCount());
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertSuccessCount(int success) {
        assertEquals("Wrong value of total success counter", success, getSuccessCount());
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertSkipCount(int skip) {
        assertEquals("Wrong value of total skip counter", skip, getSkipCount());
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertSuccessCount(int min, int max) {
        assertBetween(getSuccessCount(), min, max, "Total success counter");
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertFailureCount(int failure) {
        assertEquals("Wrong value of total failure counter", failure, getFailureCount());
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertFailureCount(int min, int max) {
        assertBetween(getFailureCount(), min, max, "Total failure counter");
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertLastSuccessObjectName(String expected) {
        assertEquals("Wrong 'last success' object name", expected, getLastSuccessObjectName());
        return this;
    }

    public ActivityItemProcessingStatisticsAsserter<RA> assertLastFailureObjectName(String expected) {
        assertEquals("Wrong 'last failure' object name", expected, getLastFailedObjectName());
        return this;
    }

    private void assertBetween(int actual, int min, int max, String label) {
        if (actual < min) {
            fail(label + " (" + actual + ") is less than minimum expected (" + min + ")");
        } else if (actual > max) {
            fail(label + " (" + actual + ") is more than maximum expected (" + max + ")");
        }
    }

    @Override
    protected String desc() {
        return getDetails();
    }

    public ActivityItemProcessingStatisticsAsserter<RA> display() {
        IntegrationTestTools.display(desc(), IterationInformation.format(information));
        return this;
    }

    private int getSuccessCount() {
        return ActivityItemProcessingStatisticsUtil.getItemsProcessedWithSuccessShallow(information);
    }

    private int getFailureCount() {
        return ActivityItemProcessingStatisticsUtil.getItemsProcessedWithFailureShallow(information);
    }

    private int getSkipCount() {
        return ActivityItemProcessingStatisticsUtil.getItemsProcessedWithSkipShallow(information);
    }

    private int getNonFailureCount() {
        return getSuccessCount() + getSkipCount();
    }

    private String getLastSuccessObjectName() {
        return ActivityItemProcessingStatisticsUtil.getLastProcessedObjectName(information, OutcomeKeyedCounterTypeUtil::isSuccess);
    }

    private String getLastFailedObjectName() {
        return ActivityItemProcessingStatisticsUtil.getLastProcessedObjectName(information, OutcomeKeyedCounterTypeUtil::isFailure);
    }
}
