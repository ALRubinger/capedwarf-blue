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

package org.jboss.capedwarf.prospectivesearch;

import com.google.appengine.api.datastore.Entity;
import com.google.appengine.api.datastore.EntityTranslator;
import com.google.appengine.api.prospectivesearch.FieldType;
import com.google.appengine.api.prospectivesearch.ProspectiveSearchService;
import com.google.appengine.api.prospectivesearch.QuerySyntaxException;
import com.google.appengine.api.prospectivesearch.Subscription;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.repackaged.com.google.common.util.Base64;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PatternAnalyzer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Version;
import org.hibernate.search.query.dsl.QueryBuilder;
import org.infinispan.Cache;
import org.infinispan.distexec.mapreduce.Collector;
import org.infinispan.distexec.mapreduce.MapReduceTask;
import org.infinispan.distexec.mapreduce.Mapper;
import org.infinispan.distexec.mapreduce.Reducer;
import org.infinispan.query.CacheQuery;
import org.infinispan.query.Search;
import org.infinispan.query.SearchManager;
import org.jboss.capedwarf.common.app.Application;
import org.jboss.capedwarf.common.infinispan.CacheName;
import org.jboss.capedwarf.common.infinispan.ConfigurationCallbacks;
import org.jboss.capedwarf.common.infinispan.InfinispanUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author <a href="mailto:mluksa@redhat.com">Marko Luksa</a>
 */
public class CapedwarfProspectiveSearchService implements ProspectiveSearchService {

    private final Logger log = Logger.getLogger(getClass().getName());

    private final Cache<TopicAndSubId, SubscriptionHolder> cache;
    private SearchManager searchManager;

    public CapedwarfProspectiveSearchService() {
        ClassLoader classLoader = Application.getAppClassloader();
        this.cache = createStore().getAdvancedCache().with(classLoader);
        this.searchManager = Search.getSearchManager(cache);
    }

    private Cache<TopicAndSubId, SubscriptionHolder> createStore() {
        return InfinispanUtils.getCache(CacheName.DEFAULT, ConfigurationCallbacks.DEFAULT_CALLBACK);
    }

    public void subscribe(String topic, String subId, long leaseDurationSeconds, String query, Map<String, FieldType> schema) {
        if (schema.isEmpty()) {
            throw new QuerySyntaxException(subId, topic, query, "schema is empty");
        }

        cache.put(new TopicAndSubId(topic, subId), new SubscriptionHolder(topic, subId, query, leaseDurationSeconds));
    }

    public void unsubscribe(String topic, String subId) {
        SubscriptionHolder holder = cache.remove(new TopicAndSubId(topic, subId));

        if (holder == null) {
            throw new IllegalArgumentException("topic '" + topic + "' has no subscription with subId " + subId);
        }
    }

    private List<Subscription> getSubscriptions(String topic) {
        Query luceneQuery = newQueryBuilder().keyword().onField("topic").matching(topic).createQuery();
        CacheQuery query = getCacheQuery(luceneQuery);
        List<Object> results = query.list();
        List<Subscription> list = new ArrayList<Subscription>(results.size());
        for (Object o : results) {
            SubscriptionHolder holder = (SubscriptionHolder) o;
            list.add(holder.toSubscription());
        }
        return list;
    }

    private CacheQuery getCacheQuery(Query luceneQuery) {
        return searchManager.getQuery(luceneQuery, SubscriptionHolder.class);
    }

    private QueryBuilder newQueryBuilder() {
        return searchManager.buildQueryBuilderForClass(SubscriptionHolder.class).get();
    }

    private List<Subscription> getSubscriptionsNullSafe(String topic) {
        List<Subscription> subscriptions = getSubscriptions(topic);
        return subscriptions == null ? Collections.<Subscription>emptyList() : subscriptions;
    }

    public void match(Entity entity, String topic) {
        match(entity, topic, "");
    }

    public void match(Entity entity, String topic, String resultKey) {
        match(entity, topic, resultKey, DEFAULT_RESULT_RELATIVE_URL, DEFAULT_RESULT_TASK_QUEUE_NAME, DEFAULT_RESULT_BATCH_SIZE, true);
    }

    public void match(Entity entity, String topic, String resultKey, String resultRelativeUrl, String resultTaskQueueName, int resultBatchSize, boolean resultReturnDocument) {
        List<Subscription> subscriptions = findMatching(entity, topic);
        addTasks(entity, subscriptions, resultRelativeUrl, resultTaskQueueName, resultBatchSize, resultReturnDocument);
    }

    private void addTasks(Entity entity, List<Subscription> subscriptions, String resultRelativeUrl, String resultTaskQueueName, int resultBatchSize, boolean resultReturnDocument) {
        Queue queue = QueueFactory.getQueue(resultTaskQueueName);

        for (int offset = 0; offset < subscriptions.size(); offset++) {
            List<Subscription> batch = subscriptions.subList(offset, Math.min(offset + resultBatchSize, subscriptions.size()));
            TaskOptions taskOptions = TaskOptions.Builder.withUrl(resultRelativeUrl)
                .param("results_offset", String.valueOf(offset))
                .param("results_count", String.valueOf(batch.size()));

            for (Subscription subscription : batch) {
                taskOptions.param("id", subscription.getId());
            }

            taskOptions.param("document", encodeDocument(entity));

            queue.add(taskOptions);
        }
    }

    private List<Subscription> findMatching(final Entity entity, final String topic) {

        final MapReduceTask<TopicAndSubId, SubscriptionHolder, String, List<Subscription>> task = new MapReduceTask<TopicAndSubId, SubscriptionHolder, String, List<Subscription>>(cache);

        final Mapper mapper = new Mapper() {
            public void map(Object key, Object value, Collector collector) {
                if (key instanceof TopicAndSubId && value instanceof SubscriptionHolder) {
                    map((TopicAndSubId) key, (SubscriptionHolder) value, (Collector<String, List<Subscription>>) collector);
                }
            }

            public void map(TopicAndSubId key, SubscriptionHolder value, Collector<String, List<Subscription>> collector) {
                try {
                    if (key.getTopic().equals(topic)) {

                        Analyzer analyzer = PatternAnalyzer.DEFAULT_ANALYZER;
                        MemoryIndex memoryIndex = new MemoryIndex();
                        for (Map.Entry<String, Object> entry : entity.getProperties().entrySet()) {
                            memoryIndex.addField(entry.getKey(), String.valueOf(entry.getValue()), analyzer);
                        }
                        QueryParser parser = new QueryParser(Version.LUCENE_35, "all", analyzer);
                        Query query = parser.parse(value.getQuery());
                        float score = memoryIndex.search(query);
                        if (score > 0.0f) {
                            collector.emit("foo", Collections.singletonList(value.toSubscription()));
                        }
                    }
                } catch (ParseException e) {
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }
        };
        Reducer reducer = new Reducer() {
            public Object reduce(Object reducedKey, Iterator iter) {
                return reduce((String) reducedKey, (Iterator<List<Subscription>>) iter);
            }

            public List<Subscription> reduce(String reducedKey, Iterator<List<Subscription>> iter) {
                List<Subscription> list = new ArrayList<Subscription>();
                while (iter.hasNext()) {
                    List<Subscription> topics = iter.next();
                    list.addAll(topics);
                }
                return list;
            }
        };

        Map<String, List<Subscription>> result = task.mappedWith(mapper).reducedWith(reducer).execute();

        List<Subscription> subscriptions = result.get("foo");

        if (subscriptions == null) {
            return Collections.emptyList();
        } else {
            return subscriptions;
        }
    }

    public List<Subscription> listSubscriptions(String topic) {
        return listSubscriptions(topic, "", DEFAULT_LIST_SUBSCRIPTIONS_MAX_RESULTS, 0);
    }

    public List<Subscription> listSubscriptions(String topic, String subIdStart, int maxResults, long expiresBefore) {
        return getSubscriptions(topic);
    }

    public Subscription getSubscription(String topic, String subId) {
        SubscriptionHolder holder = cache.get(new TopicAndSubId(topic, subId));
        if (holder == null) {
            throw new IllegalArgumentException("No subscription with topic '" + topic + "' and subId '" + subId + "'");
        } else {
            return holder.toSubscription();
        }
    }

    public List<String> listTopics(String topicStart, long maxResults) {
        MapReduceTask<TopicAndSubId, SubscriptionHolder, String, Set<String>> task = new MapReduceTask<TopicAndSubId, SubscriptionHolder, String, Set<String>>(cache);

        Mapper mapper = new Mapper() {
            public void map(Object key, Object value, Collector collector) {
                if (key instanceof TopicAndSubId && value instanceof SubscriptionHolder) {
                    map((TopicAndSubId) key, (SubscriptionHolder) value, (Collector<String, Set<String>>) collector);
                }
            }

            public void map(TopicAndSubId key, SubscriptionHolder value, Collector<String, Set<String>> collector) {
                collector.emit("foo", Collections.singleton(value.getTopic()));
            }
        };
        Reducer reducer = new Reducer() {
            public Object reduce(Object reducedKey, Iterator iter) {
                return reduce((String) reducedKey, (Iterator<Set<String>>) iter);
            }

            public Set<String> reduce(String reducedKey, Iterator<Set<String>> iter) {
                Set<String> allTopics = new TreeSet<String>();
                while (iter.hasNext()) {
                    Set<String> topics = iter.next();
                    allTopics.addAll(topics);
                }
                return allTopics;
            }
        };

        Map<String, Set<String>> result = task.mappedWith(mapper).reducedWith(reducer).execute();

        Set<String> foo = result.get("foo");
        return foo == null ? Collections.<String>emptyList() : new ArrayList<String>(foo);
    }

    public Entity getDocument(HttpServletRequest request) {
        String encodedDocument = request.getParameter("document");
        return decodeDocument(encodedDocument);
    }

    private String encodeDocument(Entity document) {
        return Base64.encodeWebSafe(EntityTranslator.convertToPb(document).toByteArray(), false);
    }

    private Entity decodeDocument(String encodedDocument) {
        try {
            return EntityTranslator.createFromPbBytes(Base64.decodeWebSafe(encodedDocument));
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not decode document: " + encodedDocument, e);
            return null;
        }
    }

    public void clear() {
        cache.clear();
    }
}
