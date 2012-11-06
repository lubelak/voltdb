/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.concurrent.Future;

import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.VoltMessage;
import org.voltcore.utils.CoreUtils;
import org.voltcore.utils.Pair;
import org.voltdb.messaging.CompleteTransactionMessage;
import org.voltdb.messaging.FragmentTaskMessage;
import org.voltdb.messaging.Iv2RepairLogRequestMessage;
import org.voltdb.messaging.Iv2RepairLogResponseMessage;

public class MpPromoteAlgo implements RepairAlgo
{
    static final VoltLogger tmLog = new VoltLogger("TM");
    private final String m_whoami;

    private final InitiatorMailbox m_mailbox;
    private final long m_requestId = System.nanoTime();
    private final List<Long> m_survivors;
    private long m_maxSeenTxnId = TxnEgo.makeZero(MpInitiator.MP_INIT_PID).getTxnId();

    // Each Term can process at most one promotion; if promotion fails, make
    // a new Term and try again (if that's your big plan...)
    private final InaugurationFuture m_promotionResult = new InaugurationFuture();

    long getRequestId()
    {
        return m_requestId;
    }

    // scoreboard for responding replica repair log responses (hsid -> response count)
    static class ReplicaRepairStruct
    {
        int m_receivedResponses = 0;
        int m_expectedResponses = -1; // (a log msg cares about this init. value)

        // update counters and return the number of outstanding messages.
        boolean update(Iv2RepairLogResponseMessage response)
        {
            m_receivedResponses++;
            m_expectedResponses = response.getOfTotal();
            return logsComplete();
        }

        // return 0 if all expected logs have been received.
        boolean logsComplete()
        {
            return (m_expectedResponses - m_receivedResponses) == 0;
        }
    }

    // replicas being processed and repaired.
    Map<Long, ReplicaRepairStruct> m_replicaRepairStructs =
        new HashMap<Long, ReplicaRepairStruct>();

    // Determine equal repair responses by the SpHandle of the response.
    Comparator<Iv2RepairLogResponseMessage> m_unionComparator =
        new Comparator<Iv2RepairLogResponseMessage>()
    {
        @Override
        public int compare(Iv2RepairLogResponseMessage o1, Iv2RepairLogResponseMessage o2)
        {
            return (int)(o1.getTxnId() - o2.getTxnId());
        }
    };

    // Union of repair responses.
    TreeSet<Iv2RepairLogResponseMessage> m_repairLogUnion =
        new TreeSet<Iv2RepairLogResponseMessage>(m_unionComparator);

    /**
     * Setup a new RepairAlgo but don't take any action to take responsibility.
     */
    public MpPromoteAlgo(List<Long> survivors, InitiatorMailbox mailbox,
            String whoami)
    {
        m_survivors = new ArrayList<Long>(survivors);
        m_mailbox = mailbox;

        m_whoami = whoami;
    }

    @Override
    public Future<Pair<Boolean, Long>> start()
    {
        try {
            prepareForFaultRecovery();
        } catch (Exception e) {
            tmLog.error(m_whoami + "failed leader promotion:", e);
            m_promotionResult.setException(e);
            m_promotionResult.done(Long.MIN_VALUE);
        }
        return m_promotionResult;
    }

    @Override
    public boolean cancel()
    {
        return m_promotionResult.cancel(false);
    }

    /** Start fixing survivors: setup scoreboard and request repair logs. */
    void prepareForFaultRecovery()
    {
        for (Long hsid : m_survivors) {
            m_replicaRepairStructs.put(hsid, new ReplicaRepairStruct());
        }

        tmLog.info(m_whoami + "found " + m_survivors.size()
                + " surviving leaders to repair. "
                + " Survivors: " + CoreUtils.hsIdCollectionToString(m_survivors));
        VoltMessage logRequest = makeRepairLogRequestMessage(m_requestId);
        m_mailbox.send(com.google.common.primitives.Longs.toArray(m_survivors), logRequest);
    }

    /** Process a new repair log response */
    @Override
    public void deliver(VoltMessage message)
    {
        if (message instanceof Iv2RepairLogResponseMessage) {
            Iv2RepairLogResponseMessage response = (Iv2RepairLogResponseMessage)message;
            if (response.getRequestId() != m_requestId) {
                tmLog.info(m_whoami + "rejecting stale repair response."
                        + " Current request id is: " + m_requestId
                        + " Received response for request id: " + response.getRequestId());
                return;
            }

            // Step 1: if the msg has a known (not MAX VALUE) handle, update m_maxSeen.
            if (response.getTxnId() != Long.MAX_VALUE) {
                m_maxSeenTxnId = Math.max(m_maxSeenTxnId, response.getTxnId());
            }

            // Step 2: offer to the union
            addToRepairLog(response);

            // Step 3: update the corresponding replica repair struct.
            ReplicaRepairStruct rrs = m_replicaRepairStructs.get(response.m_sourceHSId);
            if (rrs.m_expectedResponses < 0) {
                tmLog.info(m_whoami + "collecting " + response.getOfTotal()
                        + " repair log entries from "
                        + CoreUtils.hsIdToString(response.m_sourceHSId));
            }

            if (rrs.update(response)) {
                tmLog.info(m_whoami + "collected " + rrs.m_receivedResponses
                        + " responses for " + rrs.m_expectedResponses +
                        " repair log entries from " + CoreUtils.hsIdToString(response.m_sourceHSId));
                if (areRepairLogsComplete()) {
                    repairSurvivors();
                }
            }
        }
    }

    /** Have all survivors supplied a full repair log? */
    public boolean areRepairLogsComplete()
    {
        for (Entry<Long, ReplicaRepairStruct> entry : m_replicaRepairStructs.entrySet()) {
            if (!entry.getValue().logsComplete()) {
                return false;
            }
        }
        return true;
    }

    /** Send missed-messages to survivors. Exciting! */
    public void repairSurvivors()
    {
        // cancel() and repair() must be synchronized by the caller (the deliver lock,
        // currently). If cancelled and the last repair message arrives, don't send
        // out corrections!
        if (this.m_promotionResult.isCancelled()) {
            tmLog.debug(m_whoami + "skipping repair message creation for cancelled Term.");
            return;
        }

        tmLog.info(m_whoami + "received all repair logs and is repairing surviving replicas.");
        for (Iv2RepairLogResponseMessage li : m_repairLogUnion) {
            // send the repair log union to all the survivors. SPIs will ignore
            // CompleteTransactionMessages for transcations which have already
            // completed, so this has the effect of making sure that any holes
            // in the repair log are filled without explicitly having to
            // discover and track them.
            tmLog.debug(m_whoami + "repairing: " + m_survivors + " with: " + TxnEgo.txnIdToString(li.getTxnId()));
            m_mailbox.repairReplicasWith(m_survivors, createRepairMessage(li));
        }

        m_promotionResult.done(m_maxSeenTxnId);
    }

    //
    //
    //  Specialization
    //
    //


    VoltMessage makeRepairLogRequestMessage(long requestId)
    {
        return new Iv2RepairLogRequestMessage(requestId, Iv2RepairLogRequestMessage.MPIREQUEST);
    }

    // Always add the first message for a transaction id and always
    // replace old messages with complete transaction messages.
    void addToRepairLog(Iv2RepairLogResponseMessage msg)
    {
        // don't add the null payload from the first message ack to the repair log
        if (msg.getPayload() == null) {
            return;
        }
        Iv2RepairLogResponseMessage prev = m_repairLogUnion.floor(msg);
        if (prev != null && (prev.getTxnId() != msg.getTxnId())) {
            prev = null;
        }

        if (prev == null) {
           m_repairLogUnion.add(msg);
        }
        else if (msg.getPayload() instanceof CompleteTransactionMessage) {
            // prefer complete messages to fragment tasks.
            m_repairLogUnion.remove(prev);
            m_repairLogUnion.add(msg);
        }
    }

    VoltMessage createRepairMessage(Iv2RepairLogResponseMessage msg)
    {
        if (msg.getPayload() instanceof CompleteTransactionMessage) {
            return msg.getPayload();
        }
        else {
            FragmentTaskMessage ftm = (FragmentTaskMessage)msg.getPayload();
            CompleteTransactionMessage rollback =
                new CompleteTransactionMessage(
                        ftm.getInitiatorHSId(),
                        ftm.getCoordinatorHSId(),
                        ftm.getTxnId(),
                        ftm.isReadOnly(),
                        true,   // Force rollback as our repair operation.
                        false,  // no acks in iv2.
                        true,   // Indicate rollback for repair.
                        ftm.isForReplay());
            rollback.setOriginalTxnId(ftm.getOriginalTxnId());
            return rollback;
        }
    }

}
