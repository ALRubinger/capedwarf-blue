/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.capedwarf.datastore.query;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.Query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
public class QueryResultProcessor {

    private final Query query;
    private List<Query.FilterPredicate> inPredicates;

    public QueryResultProcessor(Query query) {
        this.query = query;
        this.inPredicates = getInPredicates(query);
    }

    public List<Entity> process(List<Entity> list) {
        if (isProcessingNeeded()) {
            return sort(list);
        } else {
            return list;
        }
    }

    public Iterator<Entity> process(Iterator<Entity> iterator) {
        if (isProcessingNeeded()) {
            List<Entity> list = toList(iterator);
            return sort(list).iterator();
        } else {
            return iterator;
        }
    }

    public boolean isProcessingNeeded() {
        return !inPredicates.isEmpty() && query.getSortPredicates().isEmpty();
    }

    public List<String> getPropertiesUsedInIn() {
        Set<String> set = new LinkedHashSet<String>();
        for (Query.FilterPredicate predicate : inPredicates) {
            set.add(predicate.getPropertyName());
        }
        return new ArrayList<String>(set);
    }

    private List<Entity> toList(Iterator<Entity> iterator) {
        List<Entity> list = new ArrayList<Entity>();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            list.add(entity);
        }
        return list;
    }

    /*
    entity1:     a=1    b=bli
    entity2:     a=2    b=bla

    query: where a IN (1, 2) AND b IN (bli, bla)

    subQuery:   a=1 b=bli   FOUND
    subQuery:   a=1 b=bla
    subQuery:   a=2 b=bli
    subQuery:   a=2 b=bla   FOUND

    result:
                a=1 b=bli
                a=2 b=bla

     */
    private List<Entity> sort(List<Entity> list) {
        Collections.sort(list, new Comparator<Entity>() {
            public int compare(Entity e1, Entity e2) {
                for (Query.FilterPredicate inPredicate : inPredicates) {
                    String propertyName = inPredicate.getPropertyName();
                    List<Object> inElements = (List<Object>) inPredicate.getValue();

                    Object v1 = e1.getProperty(propertyName);
                    Object v2 = e2.getProperty(propertyName);

                    int i1 = inElements.indexOf(v1);
                    int i2 = inElements.indexOf(v2);

                    if (i1 != i2) {
                        return i1 - i2;
                    }
                }
                return 0;
            }
        });
        removeSortProperties(list);
        return list;
    }

    private void removeSortProperties(List<Entity> list) {
        for (Entity entity : list) {
            removeSortProperties(entity);
        }
    }

    private void removeSortProperties(Entity entity) {
        for (String propertyName : Projections.getPropertiesRequiredOnlyForSorting(query)) {
            entity.removeProperty(propertyName);
        }
    }

    @SuppressWarnings("deprecation")
    private List<Query.FilterPredicate> getInPredicates(Query query) {
        List<Query.FilterPredicate> inPredicates = new LinkedList<Query.FilterPredicate>();
        addInPredicatesToList(query.getFilter(), inPredicates);
        for (Query.FilterPredicate predicate : query.getFilterPredicates()) {
            addInPredicatesToList(predicate, inPredicates);
        }
        return inPredicates;
    }

    private void addInPredicatesToList(Query.Filter filter, List<Query.FilterPredicate> inPredicates) {
        if (filter instanceof Query.CompositeFilter) {
            Query.CompositeFilter compositeFilter = (Query.CompositeFilter) filter;
            for (Query.Filter subFilter : compositeFilter.getSubFilters()) {
                addInPredicatesToList(subFilter, inPredicates);
            }
        } else if (filter instanceof Query.FilterPredicate) {
            Query.FilterPredicate predicate = (Query.FilterPredicate) filter;
            if (predicate.getOperator() == Query.FilterOperator.IN) {
                inPredicates.add(predicate);
            }
        }
    }
}
