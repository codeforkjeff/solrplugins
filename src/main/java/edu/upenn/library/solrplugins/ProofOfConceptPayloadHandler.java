/*
 * Copyright 2016 The Trustees of the University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.upenn.library.solrplugins;

import java.io.IOException;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.util.Bits;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.request.FacetPayload;

/**
 *
 * @author magibney
 */
public class ProofOfConceptPayloadHandler implements FacetPayload<NamedList<Object>> {

  @Override
  public boolean addEntry(String termKey, long count, Term t, List<Entry<LeafReader, Bits>> leaves, NamedList<NamedList<Object>> res) throws IOException {
    res.add(termKey, buildEntryValue(count, t, leaves));
    return true;
  }

  @Override
  public Map.Entry<String, NamedList<Object>> addEntry(String termKey, long count, Term t, List<Entry<LeafReader, Bits>> leaves) throws IOException {
    return new SimpleImmutableEntry<>(termKey, buildEntryValue(count, t, leaves));
  }

  private NamedList<Object> buildEntryValue(long count, Term t, List<Entry<LeafReader, Bits>> leaves) throws IOException {
    NamedList<Object> entry = new NamedList<>();
    entry.add("count", count);
    int i = -1;
    for (Entry<LeafReader, Bits> e : leaves) {
      PostingsEnum postings = e.getKey().postings(t, PostingsEnum.PAYLOADS);
      Bits liveDocs = e.getValue();
      while (postings.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
        if (!liveDocs.get(postings.docID())) {
          continue;
        }
        i++;
        NamedList<Object> documentEntry = new NamedList<>();
        entry.add("doc" + i, documentEntry);
        for (int j = 0; j < postings.freq(); j++) {
          postings.nextPosition();
          String extra = postings.getPayload().utf8ToString();
          documentEntry.add("position" + j, extra);
        }
      }
    }
    return entry;
  }

  @Override
  public NamedList<Object> mergePayload(NamedList<Object> preExisting, NamedList<Object> add, long preExistingCount, long addCount) {
    if (addCount != ((Number)add.remove("count")).longValue()) {
      throw new IllegalStateException("fieldType-internal and -external counts do not match");
    }
    int countIndex = preExisting.indexOf("count", 0);
    long preCount = ((Number)preExisting.getVal(countIndex)).longValue();
    preExisting.setVal(countIndex, preCount + addCount);
    preExisting.addAll(add);
    return preExisting;
  }

  @Override
  public long extractCount(NamedList<Object> val) {
    return ((Number) val.get("count")).longValue();
  }

  @Override
  public Object updateValueExternalRepresentation(NamedList<Object> internal) {
    return null;
  }

}
