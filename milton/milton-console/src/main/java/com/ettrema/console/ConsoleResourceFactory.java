/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.ettrema.console;

import com.bradmcevoy.http.DigestResource;
import com.bradmcevoy.http.Resource;
import com.bradmcevoy.http.ResourceFactory;
import com.bradmcevoy.http.SimpleDigestResource;
import com.bradmcevoy.http.SimpleResource;
import com.bradmcevoy.http.exceptions.BadRequestException;
import com.bradmcevoy.http.exceptions.NotAuthorizedException;
import com.bradmcevoy.io.ReadingException;
import com.bradmcevoy.io.StreamUtils;
import com.bradmcevoy.io.WritingException;

import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.output.ByteArrayOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class ConsoleResourceFactory implements ResourceFactory {

    private static final Logger log = LoggerFactory.getLogger(ConsoleResourceFactory.class);
    final ResourceFactory wrappedFactory;
    final String consolePath;
    final String consoleName;
    final String contextPath;
    final String secureResourcePath;
    final Date modDate;
    final Map<String, ConsoleCommandFactory> mapOfFactories;
    final List<ConsoleCommandFactory> factories;
    final String consolePageContent;
    final String dojoJsContent;

    public ConsoleResourceFactory(ResourceFactory wrappedFactory, String consolePath, String secureResourcePath, List<ConsoleCommandFactory> factories, String contextPath) {
        this.factories = factories;
        this.wrappedFactory = wrappedFactory;
        this.consolePath = consolePath;
        this.contextPath = contextPath;
        this.consoleName = consolePath.substring(consolePath.lastIndexOf("/"));
        this.secureResourcePath = secureResourcePath;
        this.modDate = new Date();
        mapOfFactories = new ConcurrentHashMap<String, ConsoleCommandFactory>();
        for (ConsoleCommandFactory f : factories) {
            for (String cmdName : f.getCommandNames()) {
                log.debug("Console Command Factory: " + cmdName + " - " + f.getClass());
                f.setConsoleResourceFactory(this);
                mapOfFactories.put(cmdName, f);
            }
        }
        String s = loadContent("/com/ettrema/console/console.html");
        this.consolePageContent = loadContent("console.html");
        this.dojoJsContent = loadContent("dojo.js");
    }

    @Override
    public Resource getResource(String host, String path) throws NotAuthorizedException, BadRequestException {
        try {
            if (path.startsWith(consolePath)) {
                path = stripConsolePath(path, consolePath);
                Resource secureResource = wrappedFactory.getResource(host, secureResourcePath);
                if (secureResource == null) {
                    throw new IllegalArgumentException("Could not locate a resource to authorise against. path: " + secureResourcePath + " - resourceFactory: " + wrappedFactory.getClass() + " host: " + host);
                }
                
                if (path.endsWith("index.html")) {
                    if (secureResource instanceof DigestResource) {
                        return new SimpleDigestResource("index.html", modDate, consolePageContent.getBytes("UTF-8"), "text/html", "console", (DigestResource) secureResource);
                    } else {
                        return new SimpleResource("index.html", modDate, consolePageContent.getBytes("UTF-8"), "text/html", "console", secureResource);
                    }
                } else if (path.endsWith("dojo.js")) {
                    if (secureResource instanceof DigestResource) {
                        return new SimpleDigestResource("dojo.js", modDate, dojoJsContent.getBytes("UTF-8"), "text/html", "console", (DigestResource) secureResource);
                    } else {
                        return new SimpleResource("dojo.js", modDate, dojoJsContent.getBytes("UTF-8"), "text/html", "console", secureResource);
                    }
                } else if (path.endsWith("console.json")) {
                    if (secureResource instanceof DigestResource) {
                        return new DigestConsole(host, wrappedFactory, consoleName, (DigestResource) secureResource, modDate, mapOfFactories);
                    } else {
                        return new Console(host, wrappedFactory, consoleName, secureResource, modDate, mapOfFactories);
                    }
                } else {
                    return null; // 4o4
                }
            } else {
                log.debug("not a console path");
                return wrappedFactory.getResource(host, path);
            }
        } catch (UnsupportedEncodingException unsupportedEncodingException) {
            throw new RuntimeException(unsupportedEncodingException);
        }
    }

    private String loadContent(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = Console.class.getResourceAsStream(name);
        if (in == null) {
            throw new RuntimeException("Couldnt find resource: " + name);
        }
        try {
            StreamUtils.readTo(in, out);
        } catch (ReadingException ex) {
            throw new RuntimeException(ex);
        } catch (WritingException ex) {
            throw new RuntimeException(ex);
        }
        return out.toString();
    }

    static String stripConsolePath(String path, String consolePath) {
        return path.substring(consolePath.length());
    }
}
