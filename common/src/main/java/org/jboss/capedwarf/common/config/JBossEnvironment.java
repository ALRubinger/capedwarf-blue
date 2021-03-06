/*
 *
 *  * JBoss, Home of Professional Open Source.
 *  * Copyright 2011, Red Hat, Inc., and individual contributors
 *  * as indicated by the @author tags. See the copyright.txt file in the
 *  * distribution for a full listing of individual contributors.
 *  *
 *  * This is free software; you can redistribute it and/or modify it
 *  * under the terms of the GNU Lesser General Public License as
 *  * published by the Free Software Foundation; either version 2.1 of
 *  * the License, or (at your option) any later version.
 *  *
 *  * This software is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  * Lesser General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU Lesser General Public
 *  * License along with this software; if not, write to the Free
 *  * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 *  * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 *
 */

package org.jboss.capedwarf.common.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.google.appengine.api.NamespaceManager;
import com.google.apphosting.api.ApiProxy;

/**
 * @author <a href="mailto:marko.luksa@gmail.com">Marko Luksa</a>
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class JBossEnvironment implements ApiProxy.Environment {

    private static final ThreadLocal<JBossEnvironment> threadLocalInstance = new ThreadLocal<JBossEnvironment>();
    public static final String DEFAULT_VERSION_HOSTNAME = "com.google.appengine.runtime.default_version_hostname";

    /* Impl detail ... */
    public static final String REQUEST_END_LISTENERS = "com.google.appengine.tools.development.request_end_listeners";
    private static final String REQUEST_THREAD_FACTORY_ATTR = "com.google.appengine.api.ThreadManager.REQUEST_THREAD_FACTORY";
    private static final String BACKGROUND_THREAD_FACTORY_ATTR = "com.google.appengine.api.ThreadManager.BACKGROUND_THREAD_FACTORY";

    private static final String HTTPS = "https";
    private static final String DELIMITER = "://";
    private static final int DEFAULT_HTTP_PORT = 80;

    private String email;
    private String authDomain;
    private Map<String, Object> attributes = new HashMap<String, Object>();

    private CapedwarfConfiguration capedwarfConfiguration;
    private AppEngineWebXml appEngineWebXml;
    private String baseApplicationUrl;
    private String secureBaseApplicationUrl;

    public JBossEnvironment() {
        // a bit of a workaround for LocalServiceTestHelper::tearDown NPE
        attributes.put(REQUEST_END_LISTENERS, new ArrayList());
        // add thread factory
        attributes.put(REQUEST_THREAD_FACTORY_ATTR, LazyThreadFactory.INSTANCE);
        attributes.put(BACKGROUND_THREAD_FACTORY_ATTR, LazyThreadFactory.INSTANCE);
    }

    public String getAppId() {
        assertInitialized();
        return appEngineWebXml.getApplication();
    }

    public String getVersionId() {
        assertInitialized();
        return appEngineWebXml.getVersion();
    }

    private void assertInitialized() {
        if (appEngineWebXml == null)
            throw new IllegalStateException("Application data has not been initialized. Was this method called AFTER GAEFilter did its job?");
    }

    public String getEmail() {
        return email;
    }

    public boolean isLoggedIn() {
        return email != null;
    }

    public boolean isAdmin() {
        return isLoggedIn() && capedwarfConfiguration.isAdmin(getEmail());
    }

    public String getAuthDomain() {
        return authDomain;
    }

    public String getRequestNamespace() {
        return NamespaceManager.getGoogleAppsNamespace();
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public long getRemainingMillis() {
        return 0; // TODO
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setAuthDomain(String authDomain) {
        this.authDomain = authDomain;
    }

    public void setCapedwarfConfiguration(CapedwarfConfiguration capedwarfConfiguration) {
        this.capedwarfConfiguration = capedwarfConfiguration;
    }

    public CapedwarfConfiguration getCapedwarfConfiguration() {
        return capedwarfConfiguration;
    }

    public void setAppEngineWebXml(AppEngineWebXml appEngineWebXml) {
        this.appEngineWebXml = appEngineWebXml;
    }

    public Collection<String> getAdmins() {
        return capedwarfConfiguration.getAdmins();
    }

    public void setBaseApplicationUrl(String scheme, String serverName, int port, String context) {
        String sPort = (port == DEFAULT_HTTP_PORT) ? "" : ":" + port;
        baseApplicationUrl = scheme + DELIMITER + serverName + sPort + context;
        if (HTTPS.equals(scheme)) {
            secureBaseApplicationUrl = baseApplicationUrl;
        } else {
            secureBaseApplicationUrl = HTTPS + DELIMITER + serverName + sPort + context;
        }
        attributes.put(DEFAULT_VERSION_HOSTNAME, baseApplicationUrl);
    }

    public String getBaseApplicationUrl() {
        return getBaseApplicationUrl(false);
    }

    public String getBaseApplicationUrl(boolean secureUrl) {
        return secureUrl ? secureBaseApplicationUrl : baseApplicationUrl;
    }

    public static JBossEnvironment getThreadLocalInstance() {
        JBossEnvironment environment = threadLocalInstance.get();
        if (environment == null) {
            environment = new JBossEnvironment();
            threadLocalInstance.set(environment);
        }
        return environment;
    }

    public static void clearThreadLocalInstance() {
        threadLocalInstance.set(null);
    }
}

