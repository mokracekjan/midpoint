/*
 * Copyright (c) 2012 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2012 [name of copyright owner]
 */

package com.evolveum.midpoint.wf.activiti;

import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.wf.WfConstants;
import com.evolveum.midpoint.wf.messages.*;
import com.evolveum.midpoint.xml.ns._public.communication.workflow_1.*;
import org.activiti.engine.HistoryService;
import org.activiti.engine.ProcessEngines;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.history.*;
import org.activiti.engine.runtime.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 *  Transports messages from midPoint to Activiti. (Originally via Camel, currently using direct java calls.)
 */

@Component
public class Idm2Activiti {

    @Autowired(required = true)
    ActivitiEngine activitiEngine;

    @Autowired(required = true)
    Activiti2Idm activiti2Idm;

    private static final Trace LOGGER = TraceManager.getTrace(Idm2Activiti.class);

    /**
     * Processes a message coming from midPoint to activiti
     */

    public void idm2activiti(MidPointToActivitiMessage cmd) {

        LOGGER.trace(" *** A command from midPoint has arrived; class = " + cmd.getClass().getName() + " ***");

        if (cmd instanceof QueryProcessCommand)
        {
            QueryProcessCommand qpc = (QueryProcessCommand) cmd;
            QueryProcessResponse qpr = new QueryProcessResponse();

            String pid = qpc.getPid();
            qpr.setPid(pid);
            qpr.setTaskOid(qpc.getTaskOid());

            LOGGER.trace("Querying process instance id = " + pid);
            System.out.println("#######################################################\nQuerying process instance id = " + pid);

            HistoryService hs = activitiEngine.getHistoryService();

            HistoricDetailQuery hdq = hs.createHistoricDetailQuery()
                    .variableUpdates()
                    .processInstanceId(pid)
                    //.orderByVariableRevision().desc();
                    .orderByTime().desc();

            for (HistoricDetail hd : hdq.list())
            {
                HistoricVariableUpdate hvu = (HistoricVariableUpdate) hd;
                String varname = hvu.getVariableName();
                Object value = hvu.getValue();
                LOGGER.trace("hvu: " + varname + " <- " + value);
                System.out.println("Variable: " + varname + " <- " + value + " [rev:" + hvu.getRevision() + "]");
                if (!qpr.containsVariable(varname)) {
                    qpr.putVariable(varname, value);
                }
            }

            HistoricDetailQuery hdq2 = hs.createHistoricDetailQuery()
                    .formProperties()
                    .processInstanceId(pid)
                    .orderByVariableRevision().desc();
            for (HistoricDetail hd : hdq2.list())
            {
                HistoricFormProperty hfp = (HistoricFormProperty) hd;
                String varname = hfp.getPropertyId();
                Object value = hfp.getPropertyValue();
                LOGGER.trace("form-property: " + varname + " <- " + value);
                System.out.println("form-property: " + varname + " <- " + value);
                qpr.putVariable(varname, value);
            }

            ProcessInstance pi = activitiEngine.getProcessEngine().getRuntimeService().createProcessInstanceQuery().processInstanceId(pid).singleResult();
            qpr.setRunning(pi != null && !pi.isEnded());
            System.out.println("Running process instance = " + pi + ", isRunning: " + qpr.isRunning());
            LOGGER.trace("Running process instance = " + pi + ", isRunning: " + qpr.isRunning());

            // is the process still running? (needed if value == null)
//            if (qpr.getAnswer() == null)
//            {
//                HistoricProcessInstance hip = hs.createHistoricProcessInstanceQuery()
//                        .processInstanceId(pid).singleResult();
//                if (hip != null)
//                {
//                    LOGGER.trace("Found historic process instance with id " + hip.getId() + ", end time = " + hip.getEndTime());
//                    if (hip.getEndTime() != null)
//                        ;
//                }
//                else
//                    LOGGER.trace("No historic process instance with id " + pid + " was found.");
//            }

            LOGGER.trace("Response to be sent to midPoint: " + qpr);
            activiti2Idm.onWorkflowMessage(qpr);
        }
        else if (cmd instanceof StartProcessCommand)
        {
            StartProcessCommand spic = (StartProcessCommand) cmd;

            Map<String,Object> map = new HashMap<String,Object>();

            LOGGER.trace("midpointTaskOid = " + spic.getTaskOid());

            map.put(WfConstants.MIDPOINT_TASK_OID, spic.getTaskOid());
            map.put(WfConstants.MIDPOINT_LISTENER, new IdmExecutionListenerProxy());
            map.putAll(spic.getVariables());

            LOGGER.trace("process name = " + spic.getProcessName());

            RuntimeService rs = activitiEngine.getProcessEngine().getRuntimeService();
            ProcessInstance pi = rs.startProcessInstanceByKey(spic.getProcessName(), map);

            // let us send a reply back (useful for listener-free processes)

            if (spic.isSendStartConfirmation()) {
                ProcessStartedEvent event = new ProcessStartedEvent();
                event.setTaskOid(spic.getTaskOid());
                event.setPid(pi.getProcessInstanceId());
                event.setVariablesFrom(map);
                event.setRunning(!pi.isEnded());

                LOGGER.info("Event to be sent to IDM: " + event);
                activiti2Idm.onWorkflowMessage(event);
            }
        }
        else
        {
            String message = "Unknown incoming message type: " + cmd.getClass().getName();
            LOGGER.error(message);
        }
    }

}
