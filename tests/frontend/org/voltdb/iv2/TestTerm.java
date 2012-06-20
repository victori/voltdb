/* This file is part of VoltDB.
 * Copyright (C) 2008-2012 VoltDB Inc.
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

package org.voltdb.iv2;

import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper_voltpatches.ZooKeeper;

import org.mockito.InOrder;
import static org.mockito.Mockito.*;

import org.voltdb.messaging.Iv2RepairLogResponseMessage;
import junit.framework.TestCase;
import org.junit.Test;

public class TestTerm extends TestCase
{
    Iv2RepairLogResponseMessage makeResponse(long spHandle)
    {
        Iv2RepairLogResponseMessage m = mock(Iv2RepairLogResponseMessage.class);
        when(m.getSpHandle()).thenReturn(spHandle);
        return m;
    }


    // verify that responses are correctly unioned and ordered.
    @Test
    public void testUnion() throws Exception
    {
        Term term = new Term(null, 0, 0L, null);

        // returned sphandles in a non-trivial order, with duplicates.
        long returnedSpHandles[] = new long[]{1L, 5L, 2L, 5L, 6L, 3L, 5L, 1L};
        long expectedUnion[] = new long[]{1L, 2L, 3L, 5L, 6L};

        for (long sp : returnedSpHandles) {
            term.m_repairLogUnion.add(makeResponse(sp));
        }

        assertEquals(expectedUnion.length, term.m_repairLogUnion.size());

        int i = 0;
        for (Iv2RepairLogResponseMessage li : term.m_repairLogUnion) {
            assertEquals(li.getSpHandle(), expectedUnion[i++]);
        }
    }

    // verify that the all-done logic works on replica repair structs
    @Test
    public void testRepairLogsAreComplete()
    {
        Term term = new Term(null, 0, 0L, null);
        Term.ReplicaRepairStruct notDone1 = new Term.ReplicaRepairStruct();
        notDone1.m_receivedResponses = 1;
        notDone1.m_expectedResponses = 2;
        assertTrue(notDone1.logsComplete() != 0);

        Term.ReplicaRepairStruct notDone2 = new Term.ReplicaRepairStruct();
        notDone2.m_receivedResponses = 0;
        notDone2.m_expectedResponses = 10;

        Term.ReplicaRepairStruct done1 = new Term.ReplicaRepairStruct();
        done1.m_receivedResponses = 5;
        done1.m_expectedResponses = 5;
        assertTrue(done1.logsComplete() == 0);

        Term.ReplicaRepairStruct done2 = new Term.ReplicaRepairStruct();
        done2.m_receivedResponses = 10;
        done2.m_expectedResponses = 10;

        term.m_replicaRepairStructs.put(0L, notDone1);
        term.m_replicaRepairStructs.put(1L, done1);
        term.m_replicaRepairStructs.put(2L, notDone2);
        term.m_replicaRepairStructs.put(3L, done2);
        assertFalse(term.areRepairLogsComplete());

        term.m_replicaRepairStructs.clear();
        term.m_replicaRepairStructs.put(2L, notDone2);
        assertFalse(term.areRepairLogsComplete());

        term.m_replicaRepairStructs.clear();
        term.m_replicaRepairStructs.put(1L, done1);
        term.m_replicaRepairStructs.put(3L, done2);
        assertTrue(term.areRepairLogsComplete());

        term.m_replicaRepairStructs.clear();
        term.m_replicaRepairStructs.put(3L, done2);
        assertTrue(term.areRepairLogsComplete());
    }

    // verify that Term asks initMailbox to send the expected repair messages.
    @SuppressWarnings("unchecked")
    @Test
    public void testRepairSurvivors()
    {
        InitiatorMailbox mailbox = mock(InitiatorMailbox.class);
        Term term = new Term(mock(ZooKeeper.class), 0, 0L, mailbox);

        // missing 4, 5
        Term.ReplicaRepairStruct r1 = new Term.ReplicaRepairStruct();
        r1.m_maxSpHandleSeen = 3L;

        // complete
        Term.ReplicaRepairStruct r2 = new Term.ReplicaRepairStruct();
        r2.m_maxSpHandleSeen = 5L;

        // missing 3, 4, 5
        Term.ReplicaRepairStruct r3 = new Term.ReplicaRepairStruct();
        r3.m_maxSpHandleSeen = 2L;

        term.m_replicaRepairStructs.put(1L, r1);
        term.m_replicaRepairStructs.put(2L, r2);
        term.m_replicaRepairStructs.put(3L, r3);

        // build the log
        long spHandles[] = new long[]{0L, 1L, 2L, 3L, 4L, 5L};
        Iv2RepairLogResponseMessage msgs[] = new Iv2RepairLogResponseMessage[6];
        for (int i=1; i < spHandles.length; ++i) {
            msgs[i] = makeResponse(spHandles[i]);
            term.m_repairLogUnion.add(msgs[i]);
        }

        // call the function being tested...
        term.repairSurvivors();

        List<Long> repair3 = new ArrayList<Long>();
        repair3.add(3L);
        verify(mailbox).repairReplicasWith(repair3, msgs[3]);

        List<Long> repair4And5 = new ArrayList<Long>();
        repair4And5.add(1L);
        repair4And5.add(3L);
        verify(mailbox).repairReplicasWith(repair4And5, msgs[4]);
        verify(mailbox).repairReplicasWith(repair4And5, msgs[5]);

        // verify exactly 3 repairs happened.
        verify(mailbox, times(3)).repairReplicasWith(any(repair3.getClass()), any(Iv2RepairLogResponseMessage.class));
    }

    // be a little a paranoid about order. note that the union test also verifies
    // order; unsure this is interesting... This test is on one replica because
    // I'm not sure that we guarentee the orer of iteration across replicas.
    @SuppressWarnings("unchecked")
    @Test
    public void testRepairSurvivorOrder()
    {
        InitiatorMailbox mailbox = mock(InitiatorMailbox.class);
        InOrder inOrder = inOrder(mailbox);

        Term term = new Term(mock(ZooKeeper.class), 0, 0L, mailbox);

        // missing 3, 4, 5
        Term.ReplicaRepairStruct r3 = new Term.ReplicaRepairStruct();
        r3.m_maxSpHandleSeen = 2L;

        term.m_replicaRepairStructs.put(3L, r3);

        // build the log
        long spHandles[] = new long[]{0L, 1L, 2L, 3L, 4L, 5L};
        Iv2RepairLogResponseMessage msgs[] = new Iv2RepairLogResponseMessage[6];
        for (int i=1; i < spHandles.length; ++i) {
            msgs[i] = makeResponse(spHandles[i]);
            term.m_repairLogUnion.add(msgs[i]);
        }

        // call the function being tested...
        term.repairSurvivors();

        List<Long> repair3 = new ArrayList<Long>();
        repair3.add(3L);

        // verify that r3 saw 3, 4, 5
        inOrder.verify(mailbox).repairReplicasWith(repair3, msgs[3]);
        inOrder.verify(mailbox).repairReplicasWith(repair3, msgs[4]);
        inOrder.verify(mailbox).repairReplicasWith(repair3, msgs[5]);

        // verify exactly 3 repairs happened.
        verify(mailbox, times(3)).repairReplicasWith(any(repair3.getClass()), any(Iv2RepairLogResponseMessage.class));
    }


}
