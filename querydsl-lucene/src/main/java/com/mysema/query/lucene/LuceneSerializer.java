/*
 * Copyright (c) 2010 Mysema Ltd.
 * All rights reserved.
 * 
 */
package com.mysema.query.lucene;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.search.BooleanClause.Occur;

import com.mysema.query.types.Constant;
import com.mysema.query.types.Expr;
import com.mysema.query.types.Operation;
import com.mysema.query.types.Operator;
import com.mysema.query.types.Ops;
import com.mysema.query.types.OrderSpecifier;
import com.mysema.query.types.Path;

/**
 * Serializes Querydsl queries to Lucene queries.
 *
 * @author vema
 *
 */
public class LuceneSerializer {
    
    public static final LuceneSerializer DEFAULT = new LuceneSerializer(false);
    
    public static final LuceneSerializer LOWERCASE = new LuceneSerializer(true);
    
    private final boolean lowerCase;

    protected LuceneSerializer(boolean lowerCase) {
        this.lowerCase = lowerCase;
    }

    private Query toQuery(Operation<?> operation) {
        Operator<?> op = operation.getOperator();
        if (op == Ops.OR) {
            return toTwoHandSidedQuery(operation, Occur.SHOULD);
        } else if (op == Ops.AND) {
            return toTwoHandSidedQuery(operation, Occur.MUST);
        } else if (op == Ops.NOT) {
            BooleanQuery bq = new BooleanQuery();
            bq.add(new BooleanClause(toQuery(operation.getArg(0)), Occur.MUST_NOT));
            return bq;
        } else if (op == Ops.LIKE) {
            return like(operation);
        } else if (op == Ops.EQ_OBJECT || op == Ops.EQ_PRIMITIVE || op == Ops.EQ_IGNORE_CASE) {
            return eq(operation);
        } else if (op == Ops.NE_OBJECT || op == Ops.NE_PRIMITIVE) {
            return ne(operation);
        } else if (op == Ops.STARTS_WITH || op == Ops.STARTS_WITH_IC) {
            return startsWith(operation);
        } else if (op == Ops.ENDS_WITH || op == Ops.ENDS_WITH_IC) {
            return endsWith(operation);
        } else if (op == Ops.STRING_CONTAINS || op == Ops.STRING_CONTAINS_IC) {
            return stringContains(operation);        
        } else if (op == Ops.BETWEEN) {
            return between(operation);
        } else if (op == Ops.IN){
            return in(operation);
        }
        throw new UnsupportedOperationException("Illegal operation " + operation);
    }

    private Query toTwoHandSidedQuery(Operation<?> operation, Occur occur) {
        // TODO Flatten similar queries(?)
        Query lhs = toQuery(operation.getArg(0));
        Query rhs = toQuery(operation.getArg(1));
        BooleanQuery bq = new BooleanQuery();
        bq.add(lhs, occur);
        bq.add(rhs, occur);
        return bq;
    }

    private Query like(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + normalize(s) + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, normalize(terms[0])));
    }

    private Query eq(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createTerms(operation.getArg(1));
        return eq(field, terms);
    }
    
    private Query eq(String field, String[] terms){
        if (terms.length > 1) {
            PhraseQuery pq = new PhraseQuery();
            for (String s : terms) {
                pq.add(new Term(field, normalize(s)));
            }
            return pq;
        }
        return new TermQuery(new Term(field, normalize(terms[0])));    
    }
    
    @SuppressWarnings("unchecked")
    private Query in(Operation<?> operation){
        String field = toField(operation.getArg(0));
        Collection values = (Collection) ((Constant)operation.getArg(1)).getConstant(); 
        BooleanQuery bq = new BooleanQuery();
        for (Object value : values){
            bq.add(eq(field, StringUtils.split(value.toString())), Occur.SHOULD);
        }
        return bq;
    }
    
    
    private Query ne(Operation<?> operation) {
        BooleanQuery bq = new BooleanQuery();
        bq.add(new BooleanClause(eq(operation), Occur.MUST_NOT));
        return bq;
    }

    private Query startsWith(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == 0 ? terms[i] + "*" : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, normalize(s))), Occur.MUST);
            }
            return bq;
        }
        return new PrefixQuery(new Term(field, normalize(terms[0])));
    }

    private Query stringContains(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (String s : terms) {
                bq.add(new WildcardQuery(new Term(field, "*" + normalize(s) + "*")), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + normalize(terms[0]) + "*"));
    }

    private Query endsWith(Operation<?> operation) {
        verifyArguments(operation);
        String field = toField(operation.getArg(0));
        String[] terms = createEscapedTerms(operation.getArg(1));
        if (terms.length > 1) {
            BooleanQuery bq = new BooleanQuery();
            for (int i = 0; i < terms.length; ++i) {
                String s = i == terms.length - 1 ? "*" + terms[i] : "*" + terms[i] + "*";
                bq.add(new WildcardQuery(new Term(field, normalize(s))), Occur.MUST);
            }
            return bq;
        }
        return new WildcardQuery(new Term(field, "*" + normalize(terms[0])));
    }

    private Query between(Operation<?> operation) {
        verifyArguments(operation);
        // TODO Phrase not properly supported
        String field = toField(operation.getArg(0));
        String[] lowerTerms = createTerms(operation.getArg(1));
        String[] upperTerms = createTerms(operation.getArg(2));
        return new TermRangeQuery(field, normalize(lowerTerms[0]), normalize(upperTerms[0]), true,
                true);
    }

    @SuppressWarnings("unchecked")
    private String toField(Expr<?> expr){
        if (expr instanceof Path){
            return toField((Path<?>)expr);
        }else if (expr instanceof Operation){
            Operation<?> operation = (Operation<?>) expr;
            if (operation.getOperator() == Ops.LOWER || operation.getOperator() == Ops.UPPER){
                return toField(operation.getArg(0));
            }
        }
        throw new IllegalArgumentException("Unable to transform " + expr + " to field");
    }
    
    public String toField(Path<?> path) {
        return path.getMetadata().getExpression().toString();
    }

    private void verifyArguments(Operation<?> operation) {
        List<Expr<?>> arguments = operation.getArgs();
        for (int i = 1; i < arguments.size(); ++i) {
            if (!(arguments.get(i) instanceof Constant<?>)) {
                throw new IllegalArgumentException("operation argument was not of type Constant.");
            }
        }
    }

    private String[] createTerms(Expr<?> expr) {
        return StringUtils.split(expr.toString());
    }

    private String[] createEscapedTerms(Expr<?> expr) {
        return StringUtils.split(QueryParser.escape(expr.toString()));
    }

    private String normalize(String s) {
        return lowerCase ? s.toLowerCase(Locale.ENGLISH) : s;
    }

    public Query toQuery(Expr<?> expr) {
        if (expr instanceof Operation<?>) {
            return toQuery((Operation<?>) expr);
        }
        throw new IllegalArgumentException("expr was not of type Operation");
    }

    public Sort toSort(List<OrderSpecifier<?>> orderBys){
        List<SortField> sortFields = new ArrayList<SortField>(orderBys.size());
        for (OrderSpecifier<?> orderSpecifier : orderBys) {
            if (!(orderSpecifier.getTarget() instanceof Path<?>)) {
                throw new IllegalArgumentException("argument was not of type Path.");
            }
            sortFields.add(new SortField(toField((Path<?>)orderSpecifier.getTarget()), 
                    Locale.ENGLISH, 
                    !orderSpecifier.isAscending()));
        }
        Sort sort = new Sort();
        sort.setSort(sortFields.toArray(new SortField[sortFields.size()]));
        return sort;
    }

}
