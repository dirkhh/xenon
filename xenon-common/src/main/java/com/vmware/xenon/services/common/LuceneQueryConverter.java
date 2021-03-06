/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.services.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;

import com.vmware.xenon.common.ServiceDocument;
import com.vmware.xenon.common.ServiceDocumentDescription;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification.QueryOption;
import com.vmware.xenon.services.common.QueryTask.QuerySpecification.QueryRuntimeContext;

/**
 * Convert {@link QueryTask.QuerySpecification} to native Lucene query.
 */
final class LuceneQueryConverter {
    private LuceneQueryConverter() {

    }

    static final int BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD = 16;

    static Query convert(QueryTask.Query query, QueryRuntimeContext context) {

        if (query.occurance == null) {
            query.occurance = QueryTask.Query.Occurance.MUST_OCCUR;
        }

        // Special case for top level occurance which was ignored otherwise
        if (query.booleanClauses != null) {
            if (query.term != null) {
                throw new IllegalArgumentException(
                        "term and booleanClauses are mutually exclusive");
            }

            Query booleanClauses = convertToLuceneQuery(query, context);
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            return builder.add(booleanClauses, convertToLuceneOccur(query.occurance)).build();
        }

        return convertToLuceneQuery(query, context);
    }

    static Query convertToLuceneQuery(QueryTask.Query query, QueryRuntimeContext context) {
        if (query.occurance == null) {
            query.occurance = QueryTask.Query.Occurance.MUST_OCCUR;
        }

        if (query.booleanClauses != null) {
            if (query.term != null) {
                throw new IllegalArgumentException(
                        "term and booleanClauses are mutually exclusive");
            }

            return convertToLuceneBooleanQuery(query, context);
        }

        if (query.term == null) {
            throw new IllegalArgumentException("One of term, booleanClauses must be provided");
        }

        QueryTask.QueryTerm term = query.term;
        validateTerm(term);
        if (term.matchType == null) {
            term.matchType = QueryTask.QueryTerm.MatchType.TERM;
        }

        if (context != null && query.occurance != QueryTask.Query.Occurance.MUST_NOT_OCCUR &&
                ServiceDocument.FIELD_NAME_KIND.equals(term.propertyName)) {
            if (context.kindScope == null) {
                // assume most queries contain 1 or 2 document kinds. Initialize with size 4
                // to prevent resizing when the second kind is added. The default size of 16
                // has never been filled up.
                context.kindScope = new HashSet<>(4);
            }
            context.kindScope.add(term.matchValue);
        }

        if (query.term.range != null) {
            return convertToLuceneNumericRangeQuery(query);
        } else if (query.term.matchType == QueryTask.QueryTerm.MatchType.WILDCARD) {
            return convertToLuceneWildcardTermQuery(query);
        } else if (query.term.matchType == QueryTask.QueryTerm.MatchType.PHRASE) {
            return convertToLucenePhraseQuery(query);
        } else if (query.term.matchType == QueryTask.QueryTerm.MatchType.PREFIX) {
            return convertToLucenePrefixQuery(query);
        } else {
            return convertToLuceneSingleTermQuery(query);
        }
    }

    static Query convertToLuceneSingleTermQuery(QueryTask.Query query) {
        return new TermQuery(convertToLuceneTerm(query.term));
    }

    // For language agnostic, or advanced token parsing a Tokenizer from the LUCENE
    // analysis package should be used.
    // TODO consider compiling the regular expression.
    // Currently phrase queries are considered a rare, special case.
    static Query convertToLucenePhraseQuery(QueryTask.Query query) {
        String[] tokens = query.term.matchValue.split("\\W");
        PhraseQuery.Builder builder = new PhraseQuery.Builder();
        for (String token : tokens) {
            builder.add(new Term(query.term.propertyName, token));
        }
        return builder.build();
    }

    static Query convertToLucenePrefixQuery(QueryTask.Query query) {
        // if the query is a prefix on a self link that matches all documents, then
        // special case the query to a MatchAllDocsQuery to avoid looking through
        // the entire index as the number of terms is equal to the size of the index
        if ((query.term.propertyName.equals(ServiceDocument.FIELD_NAME_SELF_LINK)) &&
                query.term.matchValue.equals(UriUtils.URI_PATH_CHAR)) {
            return new MatchAllDocsQuery();
        }
        return new PrefixQuery(convertToLuceneTerm(query.term));
    }

    static Query convertToLuceneWildcardTermQuery(QueryTask.Query query) {
        // if the query is a wildcard on a self link that matches all documents, then
        // special case the query to a MatchAllDocsQuery to avoid looking through
        // the entire index as the number of terms is equal to the size of the index
        if ((query.term.propertyName.equals(ServiceDocument.FIELD_NAME_SELF_LINK)) &&
                query.term.matchValue.equals(UriUtils.URI_WILDCARD_CHAR)) {
            return new MatchAllDocsQuery();
        }
        return new WildcardQuery(convertToLuceneTerm(query.term));
    }

    static Query convertToLuceneNumericRangeQuery(QueryTask.Query query) {
        QueryTask.QueryTerm term = query.term;

        term.range.validate();
        if (term.range.type == ServiceDocumentDescription.TypeName.LONG) {
            return createLongRangeQuery(term.propertyName, term.range);
        } else if (term.range.type == ServiceDocumentDescription.TypeName.DOUBLE) {
            return createDoubleRangeQuery(term.propertyName, term.range);
        } else if (term.range.type == ServiceDocumentDescription.TypeName.DATE) {
            // Date specifications must be in microseconds since epoch
            return createLongRangeQuery(term.propertyName, term.range);
        } else {
            throw new IllegalArgumentException("Type is not supported:"
                    + term.range.type);
        }
    }

    static Query convertToLuceneBooleanQuery(QueryTask.Query query, QueryRuntimeContext context) {
        BooleanQuery.Builder parentQuery = new BooleanQuery.Builder();

        int rewriteThreshold = Math.min(BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD, BooleanQuery.getMaxClauseCount());
        if (query.booleanClauses.size() >= rewriteThreshold) {
            List<BytesRef> termList = new ArrayList<>();
            String termKey = null;
            boolean isTermQuery = true;
            for (QueryTask.Query q : query.booleanClauses) {
                if (q.term == null || q.occurance != QueryTask.Query.Occurance.SHOULD_OCCUR
                        || (termKey != null && !termKey.equals(q.term.propertyName))) {
                    isTermQuery = false;
                    break;
                }
                termList.add(new BytesRef(q.term.matchValue));
                if (termKey == null) {
                    termKey = q.term.propertyName;
                }
            }
            // convert to a TermInSet query
            if (isTermQuery) {
                return new TermInSetQuery(termKey, termList);
            }
        }
        // Recursively build the boolean query. We allow arbitrary nesting and grouping.
        for (QueryTask.Query q : query.booleanClauses) {
            buildBooleanQuery(parentQuery, q, context);
        }
        return parentQuery.build();
    }

    static void buildBooleanQuery(BooleanQuery.Builder parent, QueryTask.Query clause,
            QueryRuntimeContext context) {
        Query lq = convertToLuceneQuery(clause, context);
        BooleanClause bc = new BooleanClause(lq, convertToLuceneOccur(clause.occurance));
        parent.add(bc);
    }

    static BooleanClause.Occur convertToLuceneOccur(QueryTask.Query.Occurance occurance) {
        if (occurance == null) {
            return BooleanClause.Occur.MUST;
        }

        switch (occurance) {
        case MUST_NOT_OCCUR:
            return BooleanClause.Occur.MUST_NOT;
        case MUST_OCCUR:
            return BooleanClause.Occur.MUST;
        case SHOULD_OCCUR:
            return BooleanClause.Occur.SHOULD;
        default:
            return BooleanClause.Occur.MUST;
        }
    }

    static Term convertToLuceneTerm(QueryTask.QueryTerm term) {
        return new Term(term.propertyName, term.matchValue);
    }

    static void validateTerm(QueryTask.QueryTerm term) {
        if (term.range == null && term.matchValue == null) {
            throw new IllegalArgumentException(
                    "One of term.matchValue, term.range is required");
        }

        if (term.range != null && term.matchValue != null) {
            throw new IllegalArgumentException(
                    "term.matchValue and term.range are exclusive of each other");
        }

        if (term.propertyName == null) {
            throw new IllegalArgumentException("term.propertyName is required");
        }
    }

    static SortField.Type convertToLuceneType(ServiceDocumentDescription.TypeName typeName) {
        if (typeName == null) {
            return SortField.Type.STRING;
        }

        switch (typeName) {

        case STRING:
            return SortField.Type.STRING;
        case DOUBLE:
            return SortField.Type.DOUBLE;
        case LONG:
            return SortField.Type.LONG;

        default:
            return SortField.Type.STRING;
        }
    }

    static Sort convertToLuceneSort(QueryTask.QuerySpecification querySpecification,
            boolean isGroupSort) {

        QueryTask.QueryTerm sortTerm = isGroupSort ? querySpecification.groupSortTerm
                : querySpecification.sortTerm;

        QueryTask.QuerySpecification.SortOrder sortOrder = isGroupSort
                ? querySpecification.groupSortOrder
                : querySpecification.sortOrder;

        if (querySpecification.options.contains(QueryOption.TOP_RESULTS)) {
            if (querySpecification.resultLimit <= 0
                    || querySpecification.resultLimit == Integer.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "resultLimit should be a positive integer less than MAX_VALUE");
            }
        }

        if (sortOrder == null) {
            if (isGroupSort) {
                querySpecification.groupSortOrder = QueryTask.QuerySpecification.SortOrder.ASC;

            } else {
                querySpecification.sortOrder = QueryTask.QuerySpecification.SortOrder.ASC;
            }
        }

        sortTerm.sortOrder = sortOrder;

        List<QueryTask.QueryTerm> additionalSortTerms = isGroupSort ? querySpecification.additionalGroupSortTerms
                : querySpecification.additionalSortTerms;

        if (additionalSortTerms == null) {
            return new Sort(convertToLuceneSortField(sortTerm));
        }

        SortField[] sortFields = new SortField[additionalSortTerms.size() + 1];
        sortFields[0] = convertToLuceneSortField(sortTerm);

        for (int index = 1; index < sortFields.length; index++) {
            sortFields[index] = convertToLuceneSortField(additionalSortTerms.get(index - 1));
        }

        return new Sort(sortFields);
    }

    private static SortField convertToLuceneSortField(QueryTask.QueryTerm sortTerm) {

        validateSortTerm(sortTerm);

        if (sortTerm.sortOrder == null) {
            sortTerm.sortOrder = QueryTask.QuerySpecification.SortOrder.ASC;
        }

        boolean order = sortTerm.sortOrder != QueryTask.QuerySpecification.SortOrder.ASC;

        SortField sortField;
        SortField.Type type = convertToLuceneType(sortTerm.propertyType);

        switch (type) {
        case LONG:
        case DOUBLE:
            sortField = new SortedNumericSortField(sortTerm.propertyName, type, order);
            break;
        default:
            sortField = new SortField(LuceneIndexDocumentHelper.createSortFieldPropertyName(sortTerm.propertyName),
                    type,
                    order);
            break;
        }
        return sortField;
    }

    static void validateSortTerm(QueryTask.QueryTerm term) {

        if (term.propertyType == null) {
            throw new IllegalArgumentException("term.propertyType is required");
        }

        if (term.propertyName == null) {
            throw new IllegalArgumentException("term.propertyName is required");
        }
    }

    private static Query createLongRangeQuery(String propertyName, QueryTask.NumericRange<?> range) {
        // The range query constructed below is based-off
        // lucene documentation as per the link:
        // https://lucene.apache.org/core/6_0_0/core/org/apache/lucene/document/LongPoint.html
        Long min = range.min == null ? Long.MIN_VALUE : range.min.longValue();
        Long max = range.max == null ? Long.MAX_VALUE : range.max.longValue();
        if (!range.isMinInclusive) {
            min = Math.addExact(min, 1);
        }
        if (!range.isMaxInclusive) {
            max = Math.addExact(max, -1);
        }
        return LongPoint.newRangeQuery(propertyName, min, max);
    }

    private static Query createDoubleRangeQuery(String propertyName, QueryTask.NumericRange<?> range) {
        // The range query constructed below is based-off
        // lucene documentation as per the link:
        // https://lucene.apache.org/core/6_0_0/core/org/apache/lucene/document/DoublePoint.html
        Double min = range.min == null ? Double.NEGATIVE_INFINITY : range.min.doubleValue();
        Double max = range.max == null ? Double.POSITIVE_INFINITY : range.max.doubleValue();
        if (!range.isMinInclusive) {
            min = Math.nextUp(min);
        }
        if (!range.isMaxInclusive) {
            max = Math.nextDown(max);
        }
        return DoublePoint.newRangeQuery(propertyName, min, max);
    }
}
