/*
 * Copyright 2015 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version
 * 2.0 (the "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.jbosson.plugins.amq.jmx;

import org.apache.activemq.artemis.api.core.management.ObjectNameBuilder;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.jbosson.plugins.amq.AmqJonTestBase;
import org.jbosson.plugins.amq.OpParameter;
import org.jbosson.plugins.amq.OperationInfo;
import org.junit.After;
import org.junit.Before;
import org.mc4j.ems.connection.ConnectionFactory;
import org.mc4j.ems.connection.EmsConnection;
import org.mc4j.ems.connection.bean.EmsBean;
import org.mc4j.ems.connection.settings.ConnectionSettings;
import org.mc4j.ems.connection.support.ConnectionProvider;
import org.mc4j.ems.connection.support.metadata.JSR160ConnectionTypeDescriptor;
import org.rhq.core.clientapi.descriptor.configuration.ConfigurationDescriptor;
import org.rhq.core.clientapi.descriptor.configuration.ConfigurationProperty;
import org.rhq.core.clientapi.descriptor.configuration.ListProperty;
import org.rhq.core.clientapi.descriptor.configuration.MapProperty;
import org.rhq.core.clientapi.descriptor.configuration.PropertyType;
import org.rhq.core.clientapi.descriptor.configuration.SimpleProperty;
import org.rhq.core.clientapi.descriptor.plugin.OperationDescriptor;

import javax.jms.Connection;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import javax.xml.bind.JAXBElement;
import java.io.IOException;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AmqJonRuntimeTestBase extends AmqJonTestBase {

   protected ActiveMQServer server;
   protected MBeanServer mbeanServer;
   protected String brokerName = "amq";
   protected ObjectNameBuilder objectNameBuilder;
   protected ConnectionFactory emsFactory;
   protected EmsConnection emsConnection;

   protected int jmxPort = 11099;

   protected List<Connection> connections = new ArrayList<Connection>();
   protected javax.jms.ConnectionFactory factory;

   private String jmxServiceURL = null;
   private JMXConnectorServer connectorServer = null;

   //make sure the jmx Registry only created once
   private static boolean jmxRegistryCreated = false;

   @Before
   public void setUp() throws Exception {
      super.setUp();
      leakCheckRule.disable();

      jmxServiceURL = "service:jmx:rmi://localhost/jndi/rmi://localhost:" + jmxPort + "/jmxrmi";
      server = createServer(true, true);
      Configuration serverConfig = server.getConfiguration();
      serverConfig.setJMXManagementEnabled(true);
      serverConfig.setName(brokerName);
      String dataDir = this.temporaryFolder.getRoot().getAbsolutePath();
      serverConfig.setPagingDirectory(dataDir + "/" + serverConfig.getPagingDirectory());
      serverConfig.setBindingsDirectory(dataDir + "/" + serverConfig.getBindingsDirectory());
      serverConfig.setLargeMessagesDirectory(dataDir + "/" + serverConfig.getLargeMessagesDirectory());
      serverConfig.setJournalDirectory(dataDir + "/" + serverConfig.getJournalDirectory());

      mbeanServer = MBeanServerFactory.createMBeanServer();
      server.setMBeanServer(mbeanServer);
      server.start();
      factory = new ActiveMQConnectionFactory("tcp://localhost:61616");
      objectNameBuilder = server.getManagementService().getObjectNameBuilder();
      connectJmx();
      System.out.println("server name: " + server.getConfiguration().getName());

      emsFactory = new ConnectionFactory();
      ConnectionSettings emsConnectionSettings = new ConnectionSettings();
      JSR160ConnectionTypeDescriptor descriptor = new JSR160ConnectionTypeDescriptor();
      emsConnectionSettings.initializeConnectionType(descriptor);
      emsConnectionSettings.setServerUrl(jmxServiceURL);

      ConnectionProvider provider = emsFactory.getConnectionProvider(emsConnectionSettings);
      emsConnection = provider.connect();
      emsConnection.loadSynchronous(true);
   }

   @After
   public void tearDown() throws Exception {
      emsConnection.close();
      connectorServer.stop();
      for (Connection conn : connections) {
         try {
            conn.close();
         } catch (Exception e) {
            //ignore
         }
      }
      server.stop();
      System.out.println("server stopped");
      super.tearDown();
   }

   private void connectJmx() throws IOException {

      if (!jmxRegistryCreated) {
         LocateRegistry.createRegistry(jmxPort);
         jmxRegistryCreated = true;
      }

      JMXServiceURL url = new JMXServiceURL(jmxServiceURL);

      connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(url, null, mbeanServer);

      connectorServer.start();
   }

   protected EmsBean getAmQServerBean() throws Exception {
      ObjectName broker = objectNameBuilder.getActiveMQServerObjectName();
      List<EmsBean> beans = emsConnection.queryBeans(broker.toString());
      assertEquals("There should be one and only broker bean", 1, beans.size());
      return beans.get(0);
   }


   protected OperationInfo getBrokerOperation(String opName, Class... types) {
      List<OperationDescriptor> ops = brokerService.getOperation();
      for (OperationDescriptor p : ops) {
         if (p.getName().equals(opName)) {
            if (paramMatch(p.getParameters(), types)) {
               return new OperationInfo(opName, types);
            }
         }
      }
      throw new IllegalArgumentException("Cannot find operation: " + opName);
   }

   protected boolean paramMatch(ConfigurationDescriptor params, Class[] types) {

      if (params == null) {
         if (types == null || types.length == 0) {
            return true;
         } else {
            return false;
         }
      }
      List<JAXBElement<? extends ConfigurationProperty>> listParams = params.getConfigurationProperty();
      if (types == null || types.length == 0) {
         if (listParams.size() == 0) {
            return true;
         } else {
            return false;
         }
      }
      if (listParams.size() != types.length) {
         return false;
      }
      for (int i = 0; i < types.length; i++) {
         JAXBElement<? extends ConfigurationProperty> elem = listParams.get(i);
         Class type1 = types[i];
         ConfigurationProperty prop = elem.getValue();
         if (prop instanceof SimpleProperty) {
            SimpleProperty simple = (SimpleProperty) prop;
            PropertyType type = simple.getType();
            if (type != OpParameter.convert(type1)) {
               return false;
            }
         } else if (prop instanceof ListProperty) {
            if (!type1.isArray() && type1 != List.class) {
               return false;
            }
         } else if (prop instanceof MapProperty) {
            if (type1 != Map.class) {
               return false;
            }
         }
      }
      return true;
   }

   protected void createConnection() throws Exception {
      Connection conn = factory.createConnection();
      connections.add(conn);
   }
}
