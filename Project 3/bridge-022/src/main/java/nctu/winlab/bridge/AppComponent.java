/*
 * Copyright 2023-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.bridge;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;
import java.util.HashMap;
import java.util.Map;

import static org.onlab.util.Tools.get;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = {SomeInterface.class},
           property = {
               "someProperty=Some Default String Value",
           })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    private LearningBridgePacketProcessor processor = new LearningBridgePacketProcessor();

    private Map<DeviceId, Map<MacAddress, PortNumber>> forwardingTable = new HashMap<>();

    private Map<MacAddress, PortNumber> macCorrespondPortTable;

    private ApplicationId appId;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class LearningBridgePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }
            packetIn(context);
        }
    }

    private void packetIn(PacketContext context) {
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        DeviceId switchID = pkt.receivedFrom().deviceId();
        MacAddress sourceMacAddress = ethPkt.getSourceMAC();
        PortNumber inPortNumber = pkt.receivedFrom().port();
        recordSourceMacAddressAndIncomingPort(switchID, sourceMacAddress, inPortNumber);
        MacAddress destinationMacAddress = ethPkt.getDestinationMAC();
        findDestinationMacAddress(context, switchID, destinationMacAddress, sourceMacAddress);
    }

    private void recordSourceMacAddressAndIncomingPort(DeviceId switchID,
        MacAddress sourceMacAddress, PortNumber inPortNumber) {
        if (!forwardingTable.containsKey(switchID)) {
            macCorrespondPortTable = new HashMap<>();
            macCorrespondPortTable.put(sourceMacAddress, inPortNumber);
            forwardingTable.put(switchID, macCorrespondPortTable);
            log.info("Add an entry to the port table of `{}`. MAC address: `{}` => Port: `{}`.",
                switchID.toString(), sourceMacAddress.toString(), inPortNumber.toString());
        } else {
            macCorrespondPortTable = forwardingTable.get(switchID);
            if (!macCorrespondPortTable.containsKey(sourceMacAddress)) {
                macCorrespondPortTable.put(sourceMacAddress, inPortNumber);
                log.info("Add an entry to the port table of `{}`. MAC address: `{}` => Port: `{}`.",
                    switchID.toString(), sourceMacAddress.toString(), inPortNumber.toString());
            }
        }
    }

    private void findDestinationMacAddress(PacketContext context,
        DeviceId switchID, MacAddress destinationMacAddress, MacAddress sourceMacAddress) {
        macCorrespondPortTable = forwardingTable.get(switchID);
        if (!macCorrespondPortTable.containsKey(destinationMacAddress)) {
            log.info("MAC address `{}` is missed on `{}`. Flood the packet.",
                destinationMacAddress.toString(), switchID.toString());
            flood(context);
        } else {
            PortNumber outputPortNumber = macCorrespondPortTable.get(destinationMacAddress);
            log.info("MAC address `{}` is matched on `{}`. Install a flow rule.",
                destinationMacAddress.toString(), switchID.toString());
            packetOut(context, outputPortNumber);
            installRule(sourceMacAddress, destinationMacAddress, outputPortNumber, switchID);
        }
    }

    private void flood(PacketContext context) {
        packetOut(context, PortNumber.FLOOD);
    }

    private void packetOut(PacketContext context, PortNumber outputPortNumber) {
        context.treatmentBuilder().setOutput(outputPortNumber);
        context.send();
    }

    private void installRule(MacAddress sourceMacAddress, MacAddress destinationMacAddress,
        PortNumber outputPortNumber, DeviceId switchID) {
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthSrc(sourceMacAddress).matchEthDst(destinationMacAddress);
        TrafficTreatment treatment;
        treatment = DefaultTrafficTreatment.builder().setOutput(outputPortNumber).build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
            .withSelector(selectorBuilder.build())
            .withTreatment(treatment)
            .withPriority(30)
            .withFlag(ForwardingObjective.Flag.VERSATILE)
            .fromApp(appId)
            .makeTemporary(30)
            .add();
        flowObjectiveService.forward(switchID, forwardingObjective);
    }
}
