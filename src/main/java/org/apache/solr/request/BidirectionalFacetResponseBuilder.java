/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.request;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map.Entry;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.Term;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRefBuilder;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.BidirectionalFacetResponseBuilder.FacetKey;
import org.apache.solr.schema.FieldType;
import org.apache.solr.search.SolrIndexSearcher;

/**
 *
 * @author magibney
 */
public class BidirectionalFacetResponseBuilder<T extends FieldType & FacetPayload, K extends FacetKey<K>> {

  public static <T extends FieldType & FacetPayload, K extends FacetKey<K>> void build(Env<T, K> env, OuterIteratorFactory<T, K> outer, InnerIteratorFactory<T, K> inner) throws IOException {
    Deque<Entry<String, Object>> entryBuilder = new ArrayDeque<>(Math.min(env.limit, 1000));
    int actualOffset;
    FacetResultIterator fri;
    if (env.offset > 0) {
      fri = outer.initialInstance(env, inner, outer);
    } else {
      fri = inner.initialInstance(env, outer);
    }
    int size = 0;
    do {
      if (fri.init()) {
        do {
          size = fri.addEntry(entryBuilder);
        } while (fri.hasNextEntry());
      }
      actualOffset = fri.getActualOffset();
      fri = fri.nextIterator(size);
    } while (fri != null);
    NamedList res = env.res;
    res.add("count", size);
    if (size > 0) {
      res.add("target_offset", actualOffset);
    }
    res.add("terms", new NamedList<Object>(entryBuilder.toArray(new Entry[entryBuilder.size()])));
  }
  
  public static interface OuterIteratorFactory<T extends FieldType & FacetPayload, K extends FacetKey<K>> {
     FacetResultIterator<T> initialInstance(Env<T, K> env, InnerIteratorFactory<T, K> inner, OuterIteratorFactory<T, K> outer) throws IOException;
     FacetResultIterator<T> finalInstance(K startIndex, int actualOffsetInit, int initialSize, Env<T, K> env) throws IOException;
  }
  
  public static interface InnerIteratorFactory<T extends FieldType & FacetPayload, K extends FacetKey<K>> {
     FacetResultIterator<T> initialInstance(Env<T, K> env, OuterIteratorFactory<T, K> outer) throws IOException;
     FacetResultIterator<T> newInstance(int actualOffsetInit, K descentStartIdx, int initialSize, Env<T, K> env, OuterIteratorFactory<T, K> outer) throws IOException;
  }
  
  public static interface FacetResultIterator<T extends FieldType & FacetPayload> {
    boolean init();
    boolean hasNextEntry();
    int addEntry(Deque<Entry<String, Object>> entryBuilder) throws IOException;
    int getActualOffset();
    FacetResultIterator<T> nextIterator(int size) throws IOException;
  }

  private static enum Provisional { NEVER, PROVISIONAL, SATISFIED }

  public static class AscendingFacetTermIteratorFactory<T extends FieldType & FacetPayload, K extends FacetKey<K>> implements InnerIteratorFactory<T, K> {

    @Override
    public FacetResultIterator<T> initialInstance(Env<T, K> env, OuterIteratorFactory<T, K> outer) throws IOException {
      return newInstance(0, env.decrementKey(env.targetKey()), 0, env, outer);
    }

    @Override
    public FacetResultIterator<T> newInstance(int actualOffsetInit, K descentStartIdx, int initialSize, Env<T, K> env, OuterIteratorFactory<T, K> outer) throws IOException {
      int off;
      int lim;
      Provisional provisional;
      if (env.offset < 0) {
        off = -env.offset;
        lim = env.limit;
        provisional = Provisional.PROVISIONAL;
      } else {
        off = 0;
        lim = env.limit - actualOffsetInit;
        provisional = Provisional.NEVER;
      }
      LimitMinder<T, K> limitMinder = new IncrementingLimitMinder(env.targetKeyInit(true));
      return new AscendingFacetTermIterator<>(provisional, limitMinder, actualOffsetInit, off, lim, descentStartIdx, initialSize, env, outer);
    }
    
  }
  
  public static class AscendingFacetTermIterator<T extends FieldType & FacetPayload, K extends FacetKey<K>> extends BaseFacetTermIterator<T, K> {

    private Provisional provisional;
    private final K descentStartIdx;
    private final OuterIteratorFactory<T, K> next;
    
    private AscendingFacetTermIterator(Provisional provisionalInit, LimitMinder limitMinder, int actualOffsetInit, int off, int lim,
        K descentStartIdx, int initialSize, Env<T, K> env, OuterIteratorFactory<T, K> next) {
      super(limitMinder, actualOffsetInit, off, lim, initialSize, env);
      this.provisional = provisionalInit;
      this.descentStartIdx = descentStartIdx;
      this.next = next;
    }
    
    @Override
    protected boolean preAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      if (provisional == Provisional.PROVISIONAL && super.preAddEntry(entryBuilder)) {
        provisional = Provisional.SATISFIED;
      }
      return true;
    }

    @Override
    protected boolean postAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      switch (provisional) {
        case SATISFIED:
          if (entryBuilder.size() > env.limit) {
            entryBuilder.removeFirst();
          }
        case NEVER:
          return super.postAddEntry(entryBuilder);
        default:
          return false;
      }
    }

    @Override
    public FacetResultIterator<T> nextIterator(int size) throws IOException {
      if (size >= env.limit) {
        return null;
      } else {
        return next.finalInstance(descentStartIdx, actualOffset, size, env);
      }
    }

  }
  
  public static class DescendingFacetTermIteratorFactory<T extends FieldType & FacetPayload, K extends FacetKey<K>> implements OuterIteratorFactory<T, K> {

    @Override
    public FacetResultIterator<T> initialInstance(Env<T, K> env, InnerIteratorFactory<T, K> inner, OuterIteratorFactory<T, K> outer) throws IOException {
      boolean finalDescent = false;
      int actualOffsetInit = 0;
      K startIndex = env.decrementKey(env.targetKey());
      LimitMinder<T, K> limitMinder = new DecrementingLimitMinder(startIndex);
      int off = Math.min(0, env.offset - env.limit);
      int lim = Math.min(env.offset, env.limit);
      return new DescendingFacetTermIterator<>(limitMinder, actualOffsetInit, off, lim, 0, env, finalDescent, inner, outer);
    }

    @Override
    public FacetResultIterator<T> finalInstance(K startIndex, int actualOffsetInit, int size, Env<T, K> env) throws IOException {
      boolean finalDescent = true;
      LimitMinder<T, K> limitMinder = new DecrementingLimitMinder(startIndex);
      int lim = env.limit - size;
      return new DescendingFacetTermIterator<>(limitMinder, actualOffsetInit, 0, lim, size, env, finalDescent, null, null);
    }
    
  }
  
  public static class DescendingFacetTermIterator<T extends FieldType & FacetPayload, K extends FacetKey<K>> extends BaseFacetTermIterator<T, K> {

    private final boolean finalDescent;
    private final InnerIteratorFactory<T, K> inner;
    private final OuterIteratorFactory<T, K> outer;
    
    private DescendingFacetTermIterator(LimitMinder<T, K> limitMinder, int actualOffsetInit, int off, int lim, int initialSize, Env<T, K> env,
        boolean finalDescent, InnerIteratorFactory<T, K> inner, OuterIteratorFactory<T, K> outer) {
      super(limitMinder, actualOffsetInit, off, lim, initialSize, env);
      this.finalDescent = finalDescent;
      this.inner = inner;
      this.outer = outer;
    }

    @Override
    protected boolean preAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      if (super.preAddEntry(entryBuilder)) {
        return true;
      } else {
        actualOffset++;
        return false;
      }
    }

    @Override
    protected boolean postAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      actualOffset++;
      return super.postAddEntry(entryBuilder);
    }

    @Override
    public FacetResultIterator<T> nextIterator(int size) throws IOException {
      if (finalDescent) {
        return null;
      } else if (actualOffset < env.limit) {
        return inner.newInstance(actualOffset, facetKey, size, env, outer);
      } else if (size < env.limit) {
        return outer.finalInstance(facetKey, actualOffset, size, env);
      } else {
        return null;
      }
    }
    
  }

  public static class BaseFacetTermIterator<T extends FieldType & FacetPayload, K extends FacetKey<K>> implements FacetResultIterator<T> {
    
    protected K facetKey;
    private final LimitMinder<T, K> limitMinder;
    protected int actualOffset;
    protected int size;
    private int off;
    private int lim;
    protected final Env<T, K> env;

    public BaseFacetTermIterator(LimitMinder<T, K> limitMinder, int actualOffsetInit, int off, int lim, int initialSize, Env<T, K> env) {
      this.limitMinder = limitMinder;
      this.actualOffset = actualOffsetInit;
      this.env = env;
      this.off = off;
      this.lim = lim;
      this.size = initialSize;
    }

    @Override
    public boolean init() {
      return (facetKey = limitMinder.startKey()) != null;
    }
    
    @Override
    public boolean hasNextEntry() {
      return (facetKey = limitMinder.nextKey(facetKey, env)) != null;
    }

    protected boolean preAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      return --off < 0;
    }
    
    protected boolean postAddEntry(Deque<Entry<String, Object>> entryBuilder) {
      return --lim <= 0;
    }
    
    @Override
    public int addEntry(Deque<Entry<String, Object>> entryBuilder) throws IOException {
      if (preAddEntry(entryBuilder)) {
        innerAddEntry(entryBuilder);
        postAddEntry(entryBuilder);
      }
      return size;
    }
    
    protected void innerAddEntry(Deque<Entry<String, Object>> entryBuilder) throws IOException {
      env.addEntry(limitMinder, facetKey, entryBuilder);
      size++;
    }

    @Override
    public FacetResultIterator<T> nextIterator(int size) throws IOException {
      return null;
    }

    @Override
    public int getActualOffset() {
      return actualOffset;
    }

  }
  
  public static interface LimitMinder<T extends FieldType & FacetPayload, K extends FacetKey<K>> {
    K startKey();
    K nextKey(K lastKey, Env<T, K> env);
    void addEntry(Entry<String, Object> entry, Deque<Entry<String, Object>> entryBuilder);
  }
  
  private static abstract class AbstractLimitMinder<T extends FieldType & FacetPayload, K extends FacetKey<K>> implements LimitMinder<T, K> {

    private final K startIndex;

    public AbstractLimitMinder(K startIndex) {
      this.startIndex = startIndex;
    }
    
    @Override
    public K startKey() {
      return startIndex;
    }

  }
  
  private static class IncrementingLimitMinder<T extends FieldType & FacetPayload, K extends FacetKey<K>> extends AbstractLimitMinder<T, K> {

    public IncrementingLimitMinder(K startIndex) {
      super(startIndex);
    }

    @Override
    public K nextKey(K lastIndex, Env<T, K> env) {
      return env.incrementKey(lastIndex);
    }

    @Override
    public void addEntry(Entry<String, Object> entry, Deque<Entry<String, Object>> entryBuilder) {
      entryBuilder.addLast(entry);
    }
    
  }
  
  private static class DecrementingLimitMinder<T extends FieldType & FacetPayload, K extends FacetKey<K>> extends AbstractLimitMinder<T, K> {

    public DecrementingLimitMinder(K startIndex) {
      super(startIndex);
    }

    @Override
    public K nextKey(K lastIndex, Env<T, K> env) {
      return env.decrementKey(lastIndex);
    }

    @Override
    public void addEntry(Entry<String, Object> entry, Deque<Entry<String, Object>> entryBuilder) {
      entryBuilder.addFirst(entry);
    }
    
  }
  
  public static interface FacetKey<K extends FacetKey<K>> extends Comparable<K> {
    
  }
  
  public static class SimpleTermIndexKey implements FacetKey<SimpleTermIndexKey> {

    public final int index;
    
    public SimpleTermIndexKey(int index) {
      this.index = index;
    }
    
    @Override
    public int compareTo(SimpleTermIndexKey o) {
      return Integer.compare(index, o.index);
    }
    
  }
  
  public static abstract class Env<T extends FieldType & FacetPayload, K extends FacetKey<K>> {
    public final int offset;
    public final int limit;
    public final int targetIdx;
    public final String targetDoc;
    public final int mincount;
    public final String fieldName;
    public final T ft;
    public final NamedList res;

    public Env(int offset, int limit, int targetIdx, String targetDoc, int mincount, String fieldName, T ft, NamedList res) {
      this.offset = offset;
      this.limit = limit;
      this.targetIdx = targetIdx;
      this.targetDoc = targetDoc;
      this.mincount = mincount;
      this.fieldName = fieldName;
      this.ft = ft;
      this.res = res;
    }
    
    public abstract K incrementKey(K previousKey);
    
    public abstract K decrementKey(K previousKey);
    
    public abstract void addEntry(LimitMinder<T, K> limitMinder, K facetKey, Deque<Entry<String, Object>> entryBuilder) throws IOException;
    
    public abstract K targetKey() throws IOException;

    public abstract K targetKeyInit(boolean ascending) throws IOException;

  }
  
  public static abstract class LocalEnv<T extends FieldType & FacetPayload, K extends FacetKey<K>> extends Env<T, K> {

    protected final int startTermIndex;
    protected final int adjust;
    protected final int nTerms;
    protected final String contains;
    protected final boolean ignoreCase;
    protected final int[] counts;
    protected final CharsRefBuilder charsRef;
    protected final boolean extend;
    protected final SortedSetDocValues si;
    protected final SolrIndexSearcher searcher;
    
    protected long currentTermCount;
    protected BytesRef currentTermBytes;
    protected String currentTerm;
    
    public LocalEnv(int offset, int limit, int startTermIndex, int adjust, int targetIdx, String targetDoc, int nTerms, String contains,
        boolean ignoreCase, int mincount, int[] counts, CharsRefBuilder charsRef, boolean extend, SortedSetDocValues si,
        SolrIndexSearcher searcher, String fieldName, T ft, NamedList res) {
      super(offset, limit, targetIdx, targetDoc, mincount, fieldName, ft, res);
      this.startTermIndex = startTermIndex;
      this.adjust = adjust;
      this.nTerms = nTerms;
      this.contains = contains;
      this.ignoreCase = ignoreCase;
      this.counts = counts;
      this.charsRef = charsRef;
      this.extend = extend;
      this.si = si;
      this.searcher = searcher;
    }
    
    protected boolean acceptTerm(int index) {
      currentTermBytes = null;
      int c = counts[index];
      if (c < mincount) {
        return false;
      }
      if (contains != null) {
        currentTermBytes = si.lookupOrd(startTermIndex + index);
        if (!SimpleFacets.contains(currentTermBytes.utf8ToString(), contains, ignoreCase)) {
          return false;
        }
      }
      if (currentTermBytes == null) {
        currentTermBytes = si.lookupOrd(startTermIndex + index);
      }
      ft.indexedToReadable(currentTermBytes, charsRef);
      currentTerm = charsRef.toString();
      currentTermCount = c;
      return true;
    }
  }
    
  public static final class LocalTermEnv<T extends FieldType & FacetPayload> extends LocalEnv<T, SimpleTermIndexKey> {

    private SimpleTermIndexKey facetKey;

    public LocalTermEnv(int offset, int limit, int startTermIndex, int adjust, int targetIdx, String targetDoc, int nTerms, String contains,
        boolean ignoreCase, int mincount, int[] counts, CharsRefBuilder charsRef, boolean extend, SortedSetDocValues si,
        SolrIndexSearcher searcher, String fieldName, T ft, NamedList res) {
      super(offset, limit, startTermIndex, adjust, targetIdx, targetDoc, nTerms, contains, ignoreCase, mincount, counts,
          charsRef, extend, si, searcher, fieldName, ft, res);
    }
    
    @Override
    public SimpleTermIndexKey incrementKey(SimpleTermIndexKey lastKey) {
      for (int i = lastKey.index + 1; i < nTerms; i++) {
        if (acceptTerm(i)) {
          facetKey = new SimpleTermIndexKey(i);
          return facetKey;
        }
      }
      return null;
    }

    @Override
    public SimpleTermIndexKey decrementKey(SimpleTermIndexKey lastKey) {
      for (int i = lastKey.index - 1; i >= adjust; i--) {
        if (acceptTerm(i)) {
          facetKey = new SimpleTermIndexKey(i);
          return facetKey;
        }
      }
      return null;
    }

    @Override
    public void addEntry(LimitMinder<T, SimpleTermIndexKey> limitMinder, SimpleTermIndexKey facetKey, Deque<Entry<String, Object>> entryBuilder) throws IOException {
      if (facetKey != this.facetKey) {
        throw new IllegalStateException();
      }
      Entry<String, Object> entry;
      if (!extend) {
        entry = new SimpleImmutableEntry<>(currentTerm, currentTermCount);
      } else {
        PostingsEnum postings = searcher.getLeafReader().postings(new Term(fieldName, currentTermBytes), PostingsEnum.PAYLOADS);
        if ((entry = ft.addEntry(currentTerm, currentTermCount, postings)) == null) {
          entry = new SimpleImmutableEntry<>(currentTerm, currentTermCount);
        }
      }
      limitMinder.addEntry(entry, entryBuilder);
    }

    @Override
    public SimpleTermIndexKey targetKey() throws IOException {
      return facetKey = new SimpleTermIndexKey((targetIdx < 0 ? ~targetIdx : targetIdx) + adjust);
    }

    @Override
    public SimpleTermIndexKey targetKeyInit(boolean ascending) throws IOException {
      int index = (targetIdx < 0 ? ~targetIdx : targetIdx) + adjust;
      SimpleTermIndexKey ret = new SimpleTermIndexKey(index);
      if (index >= adjust && index < nTerms && acceptTerm(index)) {
        return facetKey = ret;
      } else if (ascending) {
        return incrementKey(ret);
      } else {
        return decrementKey(ret);
      }
    }

  }
    
}
