/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FilteredDocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.util.Bits;

import java.io.IOException;
import java.util.Iterator;

import org.neo4j.internal.helpers.collection.BoundedIterable;
import org.neo4j.internal.helpers.collection.PrefetchingIterator;

/**
 * Provides a view of all {@link Document}s in a single partition.
 */
public class LucenePartitionAllDocumentsReader implements BoundedIterable<Document>
{
    private final SearcherReference searcherReference;
    private final IndexSearcher searcher;
    private final IndexReader reader;

    public LucenePartitionAllDocumentsReader( SearcherReference searcherReference )
    {
        this.searcherReference = searcherReference;
        this.searcher = searcherReference.getIndexSearcher();
        this.reader = searcher.getIndexReader();
    }

    @Override
    public long maxCount()
    {
        return reader.maxDoc();
    }

    @Override
    public Iterator<Document> iterator()
    {
        return new PrefetchingIterator<>()
        {
            DocIdSetIterator idIterator = iterateAllDocs();

            @Override
            protected Document fetchNextOrNull()
            {
                try
                {
                    int doc = idIterator.nextDoc();
                    if ( doc == DocIdSetIterator.NO_MORE_DOCS )
                    {
                        return null;
                    }
                    return getDocument( doc );
                }
                catch ( IOException e )
                {
                    throw new LuceneDocumentRetrievalException( "Can't fetch document id from lucene index.", e );
                }
            }
        };
    }

    @Override
    public void close() throws IOException
    {
        searcherReference.close();
    }

    private Document getDocument( int docId )
    {
        try
        {
            return searcher.doc( docId );
        }
        catch ( IOException e )
        {
            throw new LuceneDocumentRetrievalException( "Can't retrieve document with id: " + docId + ".", docId, e );
        }
    }

    private DocIdSetIterator iterateAllDocs()
    {
        DocIdSetIterator allDocs = DocIdSetIterator.all( reader.maxDoc() );
        if ( !reader.hasDeletions() )
        {
            return allDocs;
        }

        return new FilteredDocIdSetIterator( allDocs )
        {
            final Iterator<LeafReaderContext> leaves = reader.leaves().iterator();
            Bits currentLiveDocs;
            int currentMaxDoc = -1;

            @Override
            protected boolean match( int doc )
            {
                if ( doc >= currentMaxDoc && leaves.hasNext() )
                {
                    LeafReaderContext leaf = leaves.next();
                    LeafReader reader = leaf.reader();
                    currentLiveDocs = reader.getLiveDocs();
                    currentMaxDoc = reader.maxDoc();
                }
                // currentLiveDocs is allowed to be null, which means that this leaf has no deletions i.e. all docs are live
                return currentLiveDocs == null || currentLiveDocs.get( doc );
            }
        };
    }
}
