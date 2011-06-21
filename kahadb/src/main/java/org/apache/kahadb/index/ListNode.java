/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kahadb.index;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import org.apache.kahadb.page.Page;
import org.apache.kahadb.page.Transaction;
import org.apache.kahadb.util.LinkedNode;
import org.apache.kahadb.util.LinkedNodeList;
import org.apache.kahadb.util.VariableMarshaller;

/**
 * The ListNode class represents a node in the List object graph.  It is stored in
 * one overflowing Page of a PageFile.
 */
public final class ListNode<Key,Value> {
    private final static boolean ADD_FIRST = true;
    private final static boolean ADD_LAST = false;
    private final static long NOT_SET = -1;

    // The index that this node is part of.
    private final ListIndex<Key,Value> index;

    // The page associated with this node
    private Page<ListNode<Key,Value>> page;

    protected LinkedNodeList<KeyValueEntry<Key, Value>> entries = new LinkedNodeList<KeyValueEntry<Key, Value>>();

    // The next page after this one.
    private long next = NOT_SET;

    public int size(Transaction tx) {
        return entries.size();
    }

    static final class KeyValueEntry<Key, Value> extends LinkedNode<KeyValueEntry<Key, Value>> implements Entry<Key, Value>
    {
        private final Key key;
        private final Value value;

        public KeyValueEntry(Key key, Value value) {
            this.key = key;
            this.value = value;
        }

        public Key getKey() {
            return key;
        }

        public Value getValue() {
            return value;
        }

        public Value setValue(Value value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "{" + key + ":" + value + "}";
        }
    }

    private final class ListNodeIterator implements Iterator<ListNode<Key,Value>> {

        private final Transaction tx;
        ListNode<Key,Value> nextEntry;

        private ListNodeIterator(Transaction tx, ListNode<Key,Value> current) throws IOException {
            this.tx = tx;
            nextEntry = current;
        }

        public boolean hasNext() {
            return nextEntry !=null;
        }

        public ListNode<Key,Value> next() {
            ListNode<Key,Value> current = nextEntry;
            if( nextEntry !=null ) {
                if (nextEntry.next != NOT_SET) {
                    try {
                        nextEntry = index.loadNode(tx, current.next);
                    } catch (IOException unexpected) {
                        IllegalStateException e = new IllegalStateException("failed to load next: " + current.next + ", reason: " + unexpected.getLocalizedMessage());
                        e.initCause(unexpected);
                        throw e;
                    }
                } else {
                    nextEntry = null;
                }
            }
            return current;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private final class ListIterator implements Iterator<Entry<Key, Value>> {

        private final Transaction tx;
        ListNode<Key,Value> current, prev;
        KeyValueEntry<Key, Value> nextEntry;
        KeyValueEntry<Key, Value>  toRemove;

        private ListIterator(Transaction tx, ListNode<Key,Value> current, long nextIndex) throws IOException {
            this.tx = tx;
            this.current = current;
            nextEntry = current.entries.getHead();
            if (nextIndex > 0 && nextEntry != null) {
                for (long i=0; i<nextIndex; i++) {
                    nextEntry = nextEntry.getNext();
                    if (nextEntry == null) {
                        if (!nextFromNextListNode())
                            throw new NoSuchElementException("Index out of range: " + nextIndex);
                        }
                    }
                }
            }

        private boolean nextFromNextListNode() {
            boolean haveNext = false;
            if (current.getNext() != NOT_SET) {
                try {
                    prev = current;
                    current = index.loadNode(tx, current.getNext());
                } catch (IOException unexpected) {
                    NoSuchElementException e = new NoSuchElementException(unexpected.getLocalizedMessage());
                    e.initCause(unexpected);
                    throw e;
                }
                nextEntry = current.entries.getHead();
                haveNext = nextEntry != null;
            }
            return haveNext;
        }

        public boolean hasNext() {
            return nextEntry !=null || nextFromNextListNode();
        }

        public Entry<Key, Value> next() {
            if( nextEntry !=null ) {
                toRemove = nextEntry;
                nextEntry=toRemove.getNext();
                return toRemove;
            } else {
                throw new NoSuchElementException();
            }
        }

        public void remove() {
            if (toRemove == null) {
                throw new IllegalStateException("can only remove once, call next again");
            }
            try {
                doRemove(tx, current, prev, toRemove);
                index.onRemove();
                toRemove = null;
            } catch (IOException unexpected) {
                IllegalStateException e = new IllegalStateException(unexpected.getLocalizedMessage());
                e.initCause(unexpected);
                throw e;
            }
        }
    }

    /**
     * The Marshaller is used to store and load the data in the ListNode into a Page.
     *
     * @param <Key>
     * @param <Value>
     */
    static public class Marshaller<Key,Value> extends VariableMarshaller<ListNode<Key,Value>> {
        private final ListIndex<Key,Value> index;

        public Marshaller(ListIndex<Key,Value> index) {
            this.index = index;
        }

        public void writePayload(ListNode<Key,Value> node, DataOutput os) throws IOException {
            os.writeLong(node.next);
            short count = (short)node.entries.size(); // cast may truncate value...
            if( count != node.entries.size() ) {
                throw new IOException("short over flow, too many entries in list: " + node.entries.size());
            }

            os.writeShort(count);
            KeyValueEntry<Key, Value> entry = node.entries.getHead();
            while (entry != null) {
                index.getKeyMarshaller().writePayload((Key) entry.getKey(), os);
                index.getValueMarshaller().writePayload((Value) entry.getValue(), os);
                entry = entry.getNext();
            }
        }

        @SuppressWarnings("unchecked")
        public ListNode<Key,Value> readPayload(DataInput is) throws IOException {
            ListNode<Key,Value> node = new ListNode<Key,Value>(index);
            node.next = is.readLong();
            final short size = is.readShort();
            for (short i = 0; i < size; i++) {
                node.entries.addLast(
                        new KeyValueEntry(index.getKeyMarshaller().readPayload(is),
                                                     index.getValueMarshaller().readPayload(is)));
            }
            return node;
        }
    }

    public ListNode(ListIndex<Key, Value> index) {
        this.index = index;
    }

    private void doRemove(final Transaction tx, final ListNode current, final ListNode prev, KeyValueEntry<Key, Value> entry) throws IOException {
        entry.unlink();
        if (current.entries.isEmpty()) {
                if (current.getPageId() == index.getHeadPageId()) {
                    if (current.getNext() != NOT_SET) {
                        // new head
                        index.setHeadPageId(current.getNext());
                        tx.free(current.getPageId());
                    } else {
                        //  store current in empty state
                        store(tx);
                    }
                } else {
                    // need to unlink the node
                    prev.setNext(current.next);
                    index.storeNode(tx, prev, false);
                    tx.free(current.getPageId());
                }
        } else {
            store(tx);
        }
    }

    public Value put(Transaction tx, Key key, Value value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        entries.addLast(new KeyValueEntry(key, value));
        store(tx, ADD_LAST);
        return null;
    }

    public Value addFirst(Transaction tx, Key key, Value value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        entries.addFirst(new KeyValueEntry(key, value));
        store(tx, ADD_FIRST);
        return null;
    }

    private void store(Transaction tx, boolean addFirst) throws IOException {
        try {
            index.storeNode(tx, this, false);
        } catch ( Transaction.PageOverflowIOException e ) {
                // If we get an overflow
                split(tx, addFirst);
        }
    }

    private void store(Transaction tx) throws IOException {
        index.storeNode(tx, this, false);
    }

    private void split(Transaction tx, boolean isAddFirst) throws IOException {
        ListNode<Key, Value> extension = index.createNode(tx);
        if (isAddFirst) {
            // head keeps the first entry, insert extension with the rest
            extension.setNext(this.getNext());
            this.setNext(extension.getPageId());
            extension.setEntries(entries.getHead().splitAfter());
        }  else {
            index.setTailPageId(extension.getPageId());
            this.setNext(extension.getPageId());
            extension.setEntries(entries.getTail().getPrevious().splitAfter());
        }
        index.storeNode(tx, this, false);
        extension.store(tx, isAddFirst);
    }

    // called after a split
    private void setEntries(LinkedNodeList<KeyValueEntry<Key, Value>> list) {
        this.entries = list;
    }

    public Value get(Transaction tx, Key key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        Value result = null;
        KeyValueEntry<Key, Value> nextEntry = entries.getTail();
        while (nextEntry != null) {
            if (nextEntry.getKey().equals(key)) {
                result =  nextEntry.getValue();
                break;
            }
            nextEntry = nextEntry.getPrevious();
        }
        return result;
    }

    public boolean isEmpty(final Transaction tx) throws IOException {
        return entries.isEmpty();
    }

    public Entry<Key,Value> getFirst(Transaction tx) throws IOException {
        return entries.getHead();
    }

    public Entry<Key,Value> getLast(Transaction tx) throws IOException {
        return entries.getTail();
    }

    public Iterator<Entry<Key,Value>> iterator(final Transaction tx, long pos) throws IOException {
        return new ListIterator(tx, this, pos);
    }

    public Iterator<Entry<Key,Value>> iterator(final Transaction tx) throws IOException {
        return new ListIterator(tx, this, 0);
    }

    Iterator<ListNode<Key, Value>> listNodeIterator(final Transaction tx) throws IOException {
        return new ListNodeIterator(tx, this);
    }

    public void clear(Transaction tx) throws IOException {
        entries.clear();
        tx.free(this.getPageId());
    }

    public boolean contains(Transaction tx, Key key) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("Key cannot be null");
        }
        boolean found = false;
        KeyValueEntry<Key, Value> nextEntry = entries.getTail();
        while (nextEntry != null) {
            if (nextEntry.getKey().equals(key)) {
                found = true;
                break;
            }
            nextEntry = nextEntry.getPrevious();
        }
        return found;
    }

    ///////////////////////////////////////////////////////////////////
    // Implementation methods
    ///////////////////////////////////////////////////////////////////
 
    public long getPageId() {
        return page.getPageId();
    }

    public Page<ListNode<Key, Value>> getPage() {
        return page;
    }

    public void setPage(Page<ListNode<Key, Value>> page) {
        this.page = page;
    }

    public long getNext() {
        return next;
    }

    public void setNext(long next) {
        this.next = next;
    }
    
    @Override
    public String toString() {
        return "[ListNode(" + page.getPageId() + "->" + next + ") " + entries.toString() + "]";
    }
}


