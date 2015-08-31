/*
 * JBoss, Home of Professional Open Source
 * Copyright 2015 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
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

package org.jboss.arquillian.ce.wildfly;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.fabric8.kubernetes.api.model.ContainerPort;
import org.jboss.arquillian.ce.api.Replicas;
import org.jboss.arquillian.ce.protocol.CEServletProtocol;
import org.jboss.arquillian.ce.utils.AbstractCEContainer;
import org.jboss.arquillian.ce.utils.Strings;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.test.spi.TestClass;
import org.jboss.shrinkwrap.api.Archive;

/**
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class WildFlyCEContainer extends AbstractCEContainer<WildFlyCEConfiguration> {
    public Class<WildFlyCEConfiguration> getConfigurationClass() {
        return WildFlyCEConfiguration.class;
    }

    @Override
    public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription(CEServletProtocol.PROTOCOL_NAME);
    }

    public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        try {
            String imageName = buildImage(archive, "registry.access.redhat.com/jboss-eap-6/eap-openshift:6.4", "/opt/eap/standalone/deployments/");

            // clean old k8s stuff
            cleanup();

            // add new k8s config

            List<ContainerPort> ports = new ArrayList<>();
            // http
            ContainerPort http = new ContainerPort();
            http.setName("http");
            http.setContainerPort(8080);
            ports.add(http);
            // https / ssl
            ContainerPort https = new ContainerPort();
            https.setName("https");
            https.setContainerPort(8443);
            ports.add(https);
            // DMR / management
            ContainerPort mgmt = new ContainerPort();
            mgmt.setName("mgmt");
            mgmt.setContainerPort(configuration.getMgmtPort());
            ports.add(mgmt);

            int replicas = readReplicas();

            String rc = deployReplicationController(imageName, ports, "eap", replicas, configuration.getPreStopHookType(), configuration.getPreStopPath(), configuration.isIgnorePreStop());
            log.info(String.format("Deployed replication controller [%s]: %s", replicas, rc));

            return getProtocolMetaData(archive);
        } catch (Throwable t) {
            throw new DeploymentException("Cannot deploy in CE env.", t);
        }
    }

    private int readReplicas() {
        TestClass testClass = tc.get();
        Replicas replicas = testClass.getAnnotation(Replicas.class);
        int r = -1;
        if (replicas != null) {
            if (replicas.value() <= 0) {
                throw new IllegalArgumentException("Non-positive replicas size: " + replicas.value());
            }
            r = replicas.value();
        }
        int max = 0;
        for (Method c : testClass.getMethods(TargetsContainer.class)) {
            int index = Strings.parseNumber(c.getAnnotation(TargetsContainer.class).value());
            if (r > 0 && index >= r) {
                throw new IllegalArgumentException(String.format("Node / pod index bigger then replicas; %s >= %s ! (%s)", index, r, c));
            }
            max = Math.max(max, index);
        }
        if (r < 0) {
            return max + 1;
        } else {
            return r;
        }
    }

    protected void cleanup() throws Exception {
        client.cleanReplicationControllers("eaprc");
        client.cleanPods("eaprc");
    }

}
