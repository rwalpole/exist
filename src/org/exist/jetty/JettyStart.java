/*
 * eXist Open Source Native XML Database
 * Copyright (C) 2001-2014 The eXist-db Project
 * http://exist-db.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *  
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *  
 */
package org.exist.jetty;

import java.io.IOException;
import java.io.InputStream;
import java.io.LineNumberReader;
import java.io.Reader;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import javax.servlet.Servlet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.xml.XmlConfiguration;

import org.exist.SystemProperties;
import org.exist.storage.BrokerPool;
import org.exist.util.ConfigurationHelper;
import org.exist.util.FileUtils;
import org.exist.util.SingleInstanceConfiguration;
import org.exist.validation.XmlLibraryChecker;
import org.exist.xmldb.DatabaseImpl;
import org.exist.xmldb.ShutdownListener;

import org.xmldb.api.DatabaseManager;
import org.xmldb.api.base.Database;

/**
 * This class provides a main method to start Jetty with eXist. It registers shutdown
 * handlers to cleanly shut down the database and the webserver.
 * 
 * @author wolf
 */
public class JettyStart extends Observable implements LifeCycle.Listener {

    public static final String JETTY_HOME_PROP = "jetty.home";
    public static final String JETTY_BASE_PROP = "jetty.base";

    private static final String ENABLED_JETTY_CONFIG = "enabled-jetty-config";
    private static final String JETTY_PROPETIES_FILENAME = "jetty.properties";
    private static final Logger logger = LogManager.getLogger(JettyStart.class);

    public static void main(String[] args) {
        final JettyStart start = new JettyStart();
        start.run(args, null);
    }

    public final static String SIGNAL_STARTING = "jetty starting";
    public final static String SIGNAL_STARTED = "jetty started";
    public final static String SIGNAL_ERROR = "error";

    private final static int STATUS_STARTING = 0;
    private final static int STATUS_STARTED = 1;
    private final static int STATUS_STOPPING = 2;
    private final static int STATUS_STOPPED = 3;

    private int status = STATUS_STOPPED;
    private Thread shutdownHook = null;
    private int primaryPort = 8080;

    public JettyStart() {
        // Additional checks XML libs @@@@
        XmlLibraryChecker.check();
    }

    public void run() {
        final String jettyProperty = Optional.ofNullable(System.getProperty(JETTY_HOME_PROP))
                .orElseGet(() -> {
                    final Optional<Path> home = ConfigurationHelper.getExistHome();
                    final Path jettyHome = FileUtils.resolve(home, "tools").resolve("jetty");
                    final String jettyPath = jettyHome.toAbsolutePath().toString();
                    System.setProperty(JETTY_HOME_PROP, jettyPath);
                    return jettyPath;
                });

        final Path standaloneFile = Paths.get(jettyProperty).resolve("etc").resolve("standalone.xml");
        run(new String[] { standaloneFile.toAbsolutePath().toString() }, null);
    }
    
    public void run(String[] args, Observer observer) {
        if (args.length == 0) {
            logger.info("No configuration file specified!");
            return;
        }

        final Path jettyConfig = Paths.get(args[0]);

        final String shutdownHookOption = System.getProperty("exist.register-shutdown-hook", "true");
        final boolean registerShutdownHook = "true".equals(shutdownHookOption);

        final Map<String, String> configProperties;
        try {
            configProperties = getConfigProperties(jettyConfig.getParent());

            if (observer != null) {
                addObserver(observer);
            }

            // configure database
            logger.info("Configuring eXist from " + SingleInstanceConfiguration.getPath());

            logger.info("Running with Java "
                    + System.getProperty("java.version", "(unknown java.version)") + " ["
                    + System.getProperty("java.vendor", "(unknown java.vendor)") + " ("
                    + System.getProperty("java.vm.name", "(unknown java.vm.name)") + ") in "
                    + System.getProperty("java.home", "(unknown java.home)") + "]");

            logger.info("Running as user '"
                    + System.getProperty("user.name", "(unknown user.name)") + "'");
            logger.info("[eXist Home : "
                    + System.getProperty("exist.home", "unknown") + "]");
            logger.info("[eXist Version : "
                    + SystemProperties.getInstance().getSystemProperty("product-version", "unknown") + "]");
            logger.info("[eXist Build : "
                    + SystemProperties.getInstance().getSystemProperty("product-build", "unknown") + "]");
            logger.info("[Git commmit : "
                    + SystemProperties.getInstance().getSystemProperty("git-commit", "unknown") + "]");

            logger.info("[Operating System : "
                    + System.getProperty("os.name") + " "
                    + System.getProperty("os.version") + " "
                    + System.getProperty("os.arch") + "]");
            logger.info("[log4j.configurationFile : "
                    + System.getProperty("log4j.configurationFile") + "]");
            logger.info("[{} : {}]", JETTY_HOME_PROP, configProperties.get(JETTY_HOME_PROP));
            logger.info("[{} : {}]", JETTY_BASE_PROP, configProperties.get(JETTY_BASE_PROP));
            logger.info("[jetty configuration : {}]", jettyConfig.toAbsolutePath().toString());

            // we register our own shutdown hook
            BrokerPool.setRegisterShutdownHook(false);

            // configure the database instance
            SingleInstanceConfiguration config;
            if (args.length == 2) {
                config = new SingleInstanceConfiguration(args[1]);
            } else {
                config = new SingleInstanceConfiguration();
            }

            if (observer != null) {
                BrokerPool.registerStatusObserver(observer);
            }

            BrokerPool.configure(1, 5, config);

            // register the XMLDB driver
            final Database xmldb = new DatabaseImpl();
            xmldb.setProperty("create-database", "false");
            DatabaseManager.registerDatabase(xmldb);

        } catch (final Exception e) {
            logger.error("configuration error: " + e.getMessage(), e);
            e.printStackTrace();
            return;
        }

        // start Jetty
//        final Server server;
        try {
//            server = new Server();

//            try(final InputStream is = Files.newInputStream(jettyConfig)) {
//                final XmlConfiguration configuration = new XmlConfiguration(is);
//                configuration.configure(server);
//            }

            final List<Path> configFiles = getEnabledConfigFiles(jettyConfig.getParent());

            final List<Object> configuredObjects = new ArrayList();
            XmlConfiguration last = null;
            for(final Path confFile : configFiles) {
                logger.info("[loading jetty configuration : {}]", confFile.toString());
                try(final InputStream is = Files.newInputStream(confFile)) {
                    final XmlConfiguration configuration = new XmlConfiguration(is);
                    if (last != null) {
                        configuration.getIdMap().putAll(last.getIdMap());
                    }
                    configuration.getProperties().putAll(configProperties);
                    configuredObjects.add(configuration.configure());
                    last = configuration;
                }
            }

            Server server = null;
            // For all objects created by XmlConfigurations, start them if they are lifecycles.
            for (final Object configuredObject : configuredObjects) {
                if(configuredObject instanceof Server) {
                    final Server _server = (Server)configuredObject;

                    //TODO(AR) fix shutdown - Ctrl-C from command line causes NPEs

                    //setup server shutdown
                    _server.addLifeCycleListener(this);
                    BrokerPool.getInstance().registerShutdownListener(new ShutdownListenerImpl(_server));

                    if (registerShutdownHook) {
                        // register a shutdown hook for the server
                        shutdownHook = new Thread() {

                            @Override
                            public void run() {
                                setName("Shutdown");
                                BrokerPool.stopAll(true);
                                if (_server.isStopping() || _server.isStopped()) {
                                    return;
                                }

                                try {
                                    _server.stop();
                                } catch (final Exception e) {
                                    // ignore
                                }

                            }
                        };
                        Runtime.getRuntime().addShutdownHook(shutdownHook);
                    }

                    server = _server;
                }

                if (configuredObject instanceof LifeCycle) {
                    final LifeCycle lc = (LifeCycle)configuredObject;
                    if (!lc.isRunning()) {
                        logger.info("[Starting jetty component : {}]", lc.getClass().getName());
                        lc.start();
                    }
                }
            }

            //TODO(AR) server null check?

            //server.start();

            final Connector[] connectors = server.getConnectors();

            // Construct description of all ports opened.
            final StringBuilder allPorts = new StringBuilder();

            if (connectors.length > 1) {
                // plural s
                allPorts.append("s");
            }

            boolean establishedPrimaryPort = false;
            for(final Connector connector : connectors) {
                if(connector instanceof NetworkConnector) {
                    final NetworkConnector networkConnector = (NetworkConnector)connector;

                    if(!establishedPrimaryPort) {
                        this.primaryPort = networkConnector.getLocalPort();
                        establishedPrimaryPort = true;
                    }

                    allPorts.append(" ");
                    allPorts.append(networkConnector.getLocalPort());
                }
            }

            //TODO: use pluggable interface
            Class<?> openid = null;
            try {
            	openid = Class.forName("org.exist.security.realm.openid.AuthenticatorOpenIdServlet");
            } catch (final NoClassDefFoundError | ClassNotFoundException e) {
                logger.warn("Could not find OpenID extension. OpenID will be disabled!");
			}
            
            Class<?> oauth = null;
            try {
            	oauth = Class.forName("org.exist.security.realm.oauth.OAuthServlet");
            } catch (final NoClassDefFoundError | ClassNotFoundException e) {
                logger.warn("Could not find OAuthServlet extension. OAuth will be disabled!");
			}
            
            //*************************************************************

            logger.info("-----------------------------------------------------");
            logger.info("Server has started on port" + allPorts + ". Configured contexts:");

            final HandlerCollection rootHandler = (HandlerCollection)server.getHandler();
            final Handler[] handlers = rootHandler.getHandlers();
            
            for (final Handler handler: handlers) {
                
                if (handler instanceof ContextHandler) {
                    final ContextHandler contextHandler = (ContextHandler) handler;
                    logger.info("'" + contextHandler.getContextPath() + "'");
                }
            	
                //TODO: pluggable in future
                if (openid != null) {
                    if (handler instanceof ServletContextHandler) {
                        final ServletContextHandler contextHandler = (ServletContextHandler) handler;
                        contextHandler.addServlet(new ServletHolder((Class<? extends Servlet>) openid), "/openid");

                        String suffix;
                        if (contextHandler.getContextPath().endsWith("/")) {
                            suffix = "openid";
                        } else {
                            suffix = "/openid";
                        }

                        logger.info("'" + contextHandler.getContextPath() + suffix + "'");
                    }
                }

                if (oauth != null) {
                    if (handler instanceof ServletContextHandler) {
                        final ServletContextHandler contextHandler = (ServletContextHandler) handler;
                        contextHandler.addServlet(new ServletHolder((Class<? extends Servlet>) oauth), "/oauth/*");

                        String suffix;
                        if (contextHandler.getContextPath().endsWith("/")) {
                            suffix = "oauth";
                        } else {
                            suffix = "/oauth";
                        }

                        logger.info("'" + contextHandler.getContextPath() + suffix + "'");
                    }
                }
                //*************************************************************
            }

            logger.info("-----------------------------------------------------");

            setChanged();
            notifyObservers(SIGNAL_STARTED);
            
        } catch (final MultiException e) {

            // Mute the BindExceptions

            boolean hasBindException = false;
            for (final Object t : e.getThrowables()) {
                if (t instanceof java.net.BindException) {
                    hasBindException = true;
                    logger.info("----------------------------------------------------------");
                    logger.info("ERROR: Could not bind to port because " + ((Exception) t).getMessage());
                    logger.info(t.toString());
                    logger.info("----------------------------------------------------------");
                }
            }

            // If it is another error, print stacktrace
            if (!hasBindException) {
                e.printStackTrace();
            }
            setChanged();
            notifyObservers(SIGNAL_ERROR);
            
        } catch (final SocketException e) {
            logger.info("----------------------------------------------------------");
            logger.info("ERROR: Could not bind to port because " + e.getMessage());
            logger.info(e.toString());
            logger.info("----------------------------------------------------------");
            setChanged();
            notifyObservers(SIGNAL_ERROR);
            
        } catch (final Exception e) {
            e.printStackTrace();
            setChanged();
            notifyObservers(SIGNAL_ERROR);
        }
    }

    private Map<String, String> getConfigProperties(final Path configDir) throws IOException {
        final Map<String, String> configProperties = new HashMap<>();

        //load jetty.properties file
        final Path propertiesFile = configDir.resolve(JETTY_PROPETIES_FILENAME);
        if(Files.exists(propertiesFile)) {
            final Properties jettyProperties = new Properties();
            try(final Reader reader = Files.newBufferedReader(propertiesFile)) {
                jettyProperties.load(reader);
                logger.info("Loaded jetty.properties from: " + propertiesFile.toAbsolutePath().toString());

                for(final Map.Entry<Object, Object> property : jettyProperties.entrySet()) {
                    configProperties.put(property.getKey().toString(), property.getValue().toString());
                }
            }
        }

        // set or override jetty.home and jetty.base with System properties
        configProperties.put(JETTY_HOME_PROP, System.getProperty(JETTY_HOME_PROP));
        configProperties.put(JETTY_BASE_PROP, System.getProperty(JETTY_BASE_PROP, System.getProperty(JETTY_HOME_PROP)));

        return configProperties;
    }

    private List<Path> getEnabledConfigFiles(final Path configDir) throws IOException {
        final Path enabledJettyConfig = configDir.resolve(ENABLED_JETTY_CONFIG);
        if(Files.notExists(enabledJettyConfig)) {
            throw new IOException("Cannot find config enabler: "  + enabledJettyConfig.toString());
        } else {
            final List<Path> configFiles = new ArrayList<>();
            try (final LineNumberReader reader = new LineNumberReader(Files.newBufferedReader(enabledJettyConfig))) {
                String line = null;
                while ((line = reader.readLine()) != null) {
                    final String tl = line.trim();
                    if (tl.isEmpty() || tl.charAt(0) == '#') {
                        continue;
                    } else {
                        final Path configFile = configDir.resolve(tl);
                        if (Files.notExists(configFile)) {
                            throw new IOException("Cannot find enabled config: " + configFile.toString());
                        } else {
                            configFiles.add(configFile);
                        }
                    }
                }
            }
            return configFiles;
        }
    }

    public synchronized void shutdown() {
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        
        BrokerPool.stopAll(false);
        
        while (status != STATUS_STOPPED) {
            try {
                wait();
            } catch (final InterruptedException e) {
                // ignore
            }
        }
    }

    /**
     * This class gets called after the database received a shutdown request.
     *
     * @author wolf
     */
    private static class ShutdownListenerImpl implements ShutdownListener {

        private Server server;

        public ShutdownListenerImpl(Server server) {
            this.server = server;
        }

        public void shutdown(String dbname, int remainingInstances) {
            logger.info("Database shutdown: stopping server in 1sec ...");
            if (remainingInstances == 0) {
                // give the webserver a 1s chance to complete open requests
                final Timer timer = new Timer("jetty shutdown schedule", true);
                timer.schedule(new TimerTask() {
                    public void run() {
                        try {
                            // stop the server
                            server.stop();
                            server.join();
                        } catch (final Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, 1000); // timer.schedule
            }
        }
    }


    public synchronized boolean isStarted() {
        if (status == STATUS_STARTED || status == STATUS_STARTING) {
            return true;
        }
        if (status == STATUS_STOPPED) {
            return false;
        }
        while (status != STATUS_STOPPED) {
            try {
                wait();
            } catch (final InterruptedException e) {
            }
        }
        return false;
    }

    public synchronized void lifeCycleStarting(LifeCycle lifeCycle) {
        logger.info("Jetty server starting...");
        setChanged();
        notifyObservers(SIGNAL_STARTING);
        status = STATUS_STARTING;
        notifyAll();
    }

    public synchronized void lifeCycleStarted(LifeCycle lifeCycle) {
        logger.info("Jetty server started.");
        setChanged();
        notifyObservers(SIGNAL_STARTED);
        status = STATUS_STARTED;
        notifyAll();
    }

    public void lifeCycleFailure(LifeCycle lifeCycle, Throwable throwable) {
    }

    public synchronized void lifeCycleStopping(LifeCycle lifeCycle) {
        logger.info("Jetty server stopping...");
        status = STATUS_STOPPING;
        notifyAll();
    }

    public synchronized void lifeCycleStopped(LifeCycle lifeCycle) {
        logger.info("Jetty server stopped");
        status = STATUS_STOPPED;
        notifyAll();
    }

    public int getPrimaryPort() {
        return primaryPort;
    }

    public void systemInfo() {
    	BrokerPool.systemInfo();
    }
}
