/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class EsgNetwork {

    public static final String VERSION = "5.1";

    private static final Logger LOGGER = LoggerFactory.getLogger(EsgNetwork.class);

    private static final float MIN_REACTIVE_RANGE = 1f;

    private static final String UNKNOWN_REFERENCE_MESSAGE = "%s '%s' reference an unknown %s '%s'";
    private static final String ALREADY_EXISTS_MESSAGE = "%s '%s' already exists";
    private static final String DOES_NOT_EXIST_MESSAGE = "%s '%s' doesn't exist";

    private static final String AREA = "Area";
    private static final String COUPLING_DEVICE = "Coupling device";
    private static final String DC_LINK = "DC link";
    private static final String DC_NODE = "DC node";
    private static final String DETAILED_TWT = "Detailed two windings transformer";
    private static final String T3WT = "Three windings transformer";
    private static final String DISSYMMETRICAL_BRANCH = "Dissymmetrical branch";
    private static final String GENERATOR = "Generator";
    private static final String LINE = "Line";
    private static final String LOAD = "Load";
    private static final String NODE = "Node";
    private static final String SHUNT = "Capacitor or reactor bank";
    private static final String STATIC_VAR_COMPENSATOR = "Static VAR compensator";
    private static final String TRANSFORMER = "Transformer";
    private static final String VSC_CONVERTER_STATION = "VSC converter station";

    private static final String CONNECTION_NODE = "connection node";
    private static final String REGULATING_NODE = "regulating node";

    private final Map<String, EsgArea> areas = new LinkedHashMap<>();
    private final Map<String, EsgNode> nodes = new LinkedHashMap<>();
    private final Map<String, EsgLine> lines = new LinkedHashMap<>();
    private final Map<String, EsgDetailedTwoWindingTransformer> detailedTwoWindingTransformers = new LinkedHashMap<>();
    private final Map<String, EsgThreeWindingTransformer> threeWindingTransformers = new LinkedHashMap<>();
    private final Map<String, EsgDissymmetricalBranch> dissymmetricalBranches = new LinkedHashMap<>();
    private final Map<String, EsgCouplingDevice> couplingDevices = new LinkedHashMap<>();
    private final Map<String, EsgGenerator> generators = new LinkedHashMap<>();
    private final Map<String, EsgLoad> loads = new LinkedHashMap<>();
    private final Map<String, EsgCapacitorOrReactorBank> capacitorsOrReactorBanks = new LinkedHashMap<>();
    private final Map<String, EsgStaticVarCompensator> staticVarCompensators = new LinkedHashMap<>();
    private final Map<String, EsgDCNode> dcNodes = new LinkedHashMap<>();
    private final Map<String, EsgDCLink> dcLinks = new LinkedHashMap<>();
    private final Map<String, EsgAcdcVscConverter> vscConverters = new LinkedHashMap<>();

    private void checkBranchName(EsgBranchName name) {
        if (getNode(name.getNode1Name().toString()) == null) {
            throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, LINE, name, CONNECTION_NODE, name.getNode1Name()));
        }
        if (getNode(name.getNode2Name().toString()) == null) {
            throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, LINE, name, CONNECTION_NODE, name.getNode2Name()));
        }
    }

    public void checkConsistency() {
        // check there is at least one node and a slack bus
        if (nodes.size() < 1) {
            throw new EsgException("Network must have at least one node");
        }
        int slackBusCount = 0;
        for (EsgNode node : nodes.values()) {
            if (node.isSlackBus()) {
                slackBusCount++;
            }
        }
        if (slackBusCount == 0) {
            throw new EsgException("Network must have at least one slack bus");
        }
        for (EsgNode node :  getNodes()) {
            if (getArea(node.getArea().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, NODE, node.getName(), "area", node.getArea()));
            }
        }
        for (EsgLine line : getLines()) {
            checkBranchName(line.getName());
        }
        for (EsgCouplingDevice device : getCouplingDevices()) {
            checkBranchName(device.getName());
        }
        for (EsgDissymmetricalBranch branch : getDissymmetricalBranches()) {
            checkBranchName(branch.getName());
        }
        for (EsgDetailedTwoWindingTransformer transformer : getDetailedTwoWindingTransformers()) {
            checkBranchName(transformer.getName());
            if (transformer.getZbusr() != null && getNode(transformer.getZbusr().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, TRANSFORMER, transformer.getName(), REGULATING_NODE, transformer.getZbusr()));
            }
        }
        for (EsgLoad load : getLoads()) {
            if (getNode(load.getZnodlo().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, LOAD, load.getZnamlo(), CONNECTION_NODE, load.getZnodlo()));
            }
        }
        for (EsgGenerator generator : getGenerators()) {
            if (getNode(generator.getZnodge().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, GENERATOR, generator.getZnamge(), CONNECTION_NODE, generator.getZnodge()));
            }
            if (getNode(generator.getZregnoge().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, GENERATOR, generator.getZnamge(), REGULATING_NODE, generator.getZregnoge()));
            }
        }
        for (EsgCapacitorOrReactorBank bank : getCapacitorOrReactorBanks()) {
            if (getNode(bank.getZnodba().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, SHUNT, bank.getZnamba(), CONNECTION_NODE, bank.getZnodba()));
            }
        }
        for (EsgStaticVarCompensator svc : getStaticVarCompensators()) {
            if (getNode(svc.getZnodsvc().toString()) == null) {
                throw new EsgException(String.format(UNKNOWN_REFERENCE_MESSAGE, STATIC_VAR_COMPENSATOR, svc.getZnamsvc(), CONNECTION_NODE, svc.getZnodsvc()));
            }
        }

        // Fix generator small reactive range issue
        List<String> minReactiveRangePb = new ArrayList<>();
        for (EsgGenerator g : getGenerators()) {
            if (g.getXregge() == EsgRegulatingMode.REGULATING && Math.abs(g.getQgmax() - g.getQgmin()) < MIN_REACTIVE_RANGE) {
                minReactiveRangePb.add(g.getZnamge().toString());
                g.setXregge(EsgRegulatingMode.NOT_REGULATING);
            }
        }
        if (!minReactiveRangePb.isEmpty()) {
            LOGGER.warn("Reactive range too small, switch regulator off: {}", minReactiveRangePb);
        }

        // Fix target voltage consistency issue
        // Eurostag error message example:
        // ERR-0194.0350:LE GEN CURBH6G0 ESSAIE D'IMPOSER UNE TENSION AU NOEUD BARNAP71 AUQUEL UN AUTRE EQUIPEMENT A DEJA IMPOSE UNE AUTRE TENSION
        Multimap<String, EsgGenerator> generatorsConnectedToSameNode = HashMultimap.create();
        for (EsgGenerator g : getGenerators()) {
            if (g.getXregge() == EsgRegulatingMode.REGULATING) {
                generatorsConnectedToSameNode.put(g.getZnodge().toString(), g);
            }
        }
        for (Map.Entry<String, Collection<EsgGenerator>> e : generatorsConnectedToSameNode.asMap().entrySet()) {
            String nodeName = e.getKey();
            Collection<EsgGenerator> gens = e.getValue();
            Set<Double> targetVoltageSet = gens.stream()
                    .map(EsgGenerator::getVregge)
                    .collect(Collectors.toSet());
            if (targetVoltageSet.size() > 1) {
                Collection<EsgGenerator> connectedGenerators = gens.stream()
                        .filter(g -> g.getXgenest() == EsgConnectionStatus.CONNECTED)
                        .collect(Collectors.toList());
                targetVoltageSet = connectedGenerators.stream()
                        .map(EsgGenerator::getVregge)
                        .collect(Collectors.toSet());
                if (!targetVoltageSet.isEmpty()) {
                    if (targetVoltageSet.size() == 1) {
                        Collection<EsgGenerator> diconnectedGenerators = gens.stream()
                                .filter(g -> g.getXgenest() == EsgConnectionStatus.NOT_CONNECTED)
                                .collect(Collectors.toList());
                        LOGGER.warn("Fix target voltage of disconnected generators {} to be consistent with target voltage ({} Kv) of other generators connected to the same node ({})",
                                diconnectedGenerators.stream().map(EsgGenerator::getZnamge).collect(Collectors.toList()),
                                targetVoltageSet.iterator().next(), nodeName);
                        double vregge = targetVoltageSet.iterator().next();
                        for (EsgGenerator g : diconnectedGenerators) {
                            g.setVregge(vregge);
                        }
                    } else {
                        throw new EsgException(connectedGenerators.size() + " generators ("
                                + connectedGenerators.stream().map(EsgGenerator::getZnamge).collect(Collectors.toList())
                                + ") are connected to a same node (" + nodeName + ") and try to impose a different target voltage: "
                                + targetVoltageSet);
                    }
                }
            }
        }

        // check there is no regulating transformer connected to same bus with a different target voltage
        Multimap<Esg8charName, EsgDetailedTwoWindingTransformer> transformersByRegulatedNode = HashMultimap.create();
        for (EsgDetailedTwoWindingTransformer transfo : getDetailedTwoWindingTransformers()) {
            if (transfo.getXregtr() == EsgDetailedTwoWindingTransformer.RegulatingMode.VOLTAGE) {
                transformersByRegulatedNode.put(transfo.getZbusr(), transfo);
            }
        }
        for (Map.Entry<Esg8charName, Collection<EsgDetailedTwoWindingTransformer>> e : transformersByRegulatedNode.asMap().entrySet()) {
            Esg8charName regulatedNode = e.getKey();
            Collection<EsgDetailedTwoWindingTransformer> transformers = e.getValue();

            OptionalDouble chosenTargetVoltage = transformers.stream()
                    .mapToDouble(EsgDetailedTwoWindingTransformer::getVoltr)
                    .min();
            if (chosenTargetVoltage.isPresent()) {
                LOGGER.warn("Fix target voltage of transformers {} connected to same regulating bus {} to {} kV",
                        transformers.stream().map(EsgDetailedTwoWindingTransformer::getName).collect(Collectors.toList()),
                        regulatedNode, chosenTargetVoltage.getAsDouble());
                for (EsgDetailedTwoWindingTransformer transformer : transformers) {
                    transformer.setVoltr(chosenTargetVoltage.getAsDouble());
                }
            }
        }
    }

    public Collection<EsgArea> getAreas() {
        return areas.values();
    }

    public EsgArea getArea(String name) {
        return areas.get(name);
    }

    public void addArea(EsgArea area) {
        if (areas.containsKey(area.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, AREA, area.getName()));
        }
        areas.put(area.getName().toString(), area);
    }

    public void removeArea(String area) {
        if (!areas.containsKey(area)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, AREA, area));
        }
        areas.remove(area);
    }

    public Collection<EsgNode> getNodes() {
        return nodes.values();
    }

    public EsgNode getNode(String name) {
        return nodes.get(name);
    }

    public void addNode(EsgNode node) {
        if (nodes.containsKey(node.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, NODE, node.getName()));
        }
        nodes.put(node.getName().toString(), node);
    }

    public void removeNode(String node) {
        if (!nodes.containsKey(node)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, NODE, node));
        }
        nodes.remove(node);
    }

    public Collection<EsgLine> getLines() {
        return lines.values();
    }

    public EsgLine getLine(String name) {
        return lines.get(name);
    }

    public void addLine(EsgLine line) {
        if (lines.containsKey(line.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, LINE, line.getName()));
        }
        lines.put(line.getName().toString(), line);
    }

    public void removeLine(String line) {
        if (!lines.containsKey(line)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, LINE, line));
        }
        lines.remove(line);
    }

    public Collection<EsgDetailedTwoWindingTransformer> getDetailedTwoWindingTransformers() {
        return detailedTwoWindingTransformers.values();
    }

    public EsgDetailedTwoWindingTransformer getDetailedTwoWindingTransformer(String name) {
        return detailedTwoWindingTransformers.get(name);
    }

    public void addDetailedTwoWindingTransformer(EsgDetailedTwoWindingTransformer transformer) {
        if (detailedTwoWindingTransformers.containsKey(transformer.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, DETAILED_TWT, transformer));
        }
        detailedTwoWindingTransformers.put(transformer.getName().toString(), transformer);
    }

    public void removeDetailedTwoWindingTransformer(String transformer) {
        if (!detailedTwoWindingTransformers.containsKey(transformer)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, DETAILED_TWT, transformer));
        }
        detailedTwoWindingTransformers.remove(transformer);
    }

    public void addThreeWindingTransformer(EsgThreeWindingTransformer t3wTransformer) {
        if (threeWindingTransformers.containsKey(t3wTransformer.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, T3WT, t3wTransformer));
        }
        threeWindingTransformers.put(t3wTransformer.getName().toString(), t3wTransformer);
    }

    public Collection<EsgThreeWindingTransformer> getThreeWindingTransformers() {
        return threeWindingTransformers.values();
    }

    public Collection<EsgDissymmetricalBranch> getDissymmetricalBranches() {
        return dissymmetricalBranches.values();
    }

    public EsgDissymmetricalBranch getDissymmetricalBranch(String name) {
        return dissymmetricalBranches.get(name);
    }

    public void addDissymmetricalBranch(EsgDissymmetricalBranch branch) {
        if (dissymmetricalBranches.containsKey(branch.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, DISSYMMETRICAL_BRANCH, branch));
        }
        dissymmetricalBranches.put(branch.getName().toString(), branch);
    }

    public void removeDissymmetricalBranch(String branch) {
        if (!dissymmetricalBranches.containsKey(branch)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, DISSYMMETRICAL_BRANCH, branch));
        }
        dissymmetricalBranches.remove(branch);
    }

    public Collection<EsgCouplingDevice> getCouplingDevices() {
        return couplingDevices.values();
    }

    public EsgCouplingDevice getCouplingDevice(String name) {
        return couplingDevices.get(name);
    }

    public void addCouplingDevice(EsgCouplingDevice device) {
        if (couplingDevices.containsKey(device.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, COUPLING_DEVICE, device));
        }
        couplingDevices.put(device.getName().toString(), device);
    }

    public void removeCouplingDevice(String device) {
        if (!couplingDevices.containsKey(device)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, COUPLING_DEVICE, device));
        }
        couplingDevices.remove(device);
    }

    public Collection<EsgGenerator> getGenerators() {
        return generators.values();
    }

    public EsgGenerator getGenerator(String name) {
        return generators.get(name);
    }

    public void addGenerator(EsgGenerator generator) {
        if (generators.containsKey(generator.getZnamge().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, GENERATOR, generator.getZnamge()));
        }
        generators.put(generator.getZnamge().toString(), generator);
    }

    public void removeGenerator(String generator) {
        if (!generators.containsKey(generator)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, GENERATOR, generator));
        }
        generators.remove(generator);
    }

    public Collection<EsgLoad> getLoads() {
        return loads.values();
    }

    public EsgLoad getLoad(String name) {
        return loads.get(name);
    }

    public void addLoad(EsgLoad load) {
        if (loads.containsKey(load.getZnamlo().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, LOAD, load.getZnamlo()));
        }
        loads.put(load.getZnamlo().toString(), load);
    }

    public void removeLoad(String load) {
        if (!loads.containsKey(load)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, LOAD, load));
        }
        loads.remove(load);
    }

    public Collection<EsgCapacitorOrReactorBank> getCapacitorOrReactorBanks() {
        return capacitorsOrReactorBanks.values();
    }

    public EsgCapacitorOrReactorBank getCapacitorOrReactorBank(String name) {
        return capacitorsOrReactorBanks.get(name);
    }

    public void addCapacitorsOrReactorBanks(EsgCapacitorOrReactorBank bank) {
        if (capacitorsOrReactorBanks.containsKey(bank.getZnamba().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, SHUNT, bank.getZnamba()));
        }
        capacitorsOrReactorBanks.put(bank.getZnamba().toString(), bank);
    }

    public void removeCapacitorsOrReactorBanks(String bank) {
        if (!capacitorsOrReactorBanks.containsKey(bank)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, SHUNT, bank));
        }
        capacitorsOrReactorBanks.remove(bank);
    }

    public Collection<EsgStaticVarCompensator> getStaticVarCompensators() {
        return staticVarCompensators.values();
    }

    public void addStaticVarCompensator(EsgStaticVarCompensator svc) {
        if (staticVarCompensators.containsKey(svc.getZnamsvc().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, STATIC_VAR_COMPENSATOR, svc.getZnamsvc()));
        }
        staticVarCompensators.put(svc.getZnamsvc().toString(), svc);
    }

    public Collection<EsgDCNode> getDCNodes() {
        return dcNodes.values();
    }

    public EsgDCNode getDCNode(String name) {
        return dcNodes.get(name);
    }

    public void addDCNode(EsgDCNode node) {
        if (dcNodes.containsKey(node.getName().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, DC_NODE, node.getName()));
        }
        dcNodes.put(node.getName().toString(), node);
    }

    public void removeDCNode(String node) {
        if (!dcNodes.containsKey(node)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, DC_NODE, node));
        }
        dcNodes.remove(node);
    }

    public Collection<EsgDCLink> getDCLinks() {
        return dcLinks.values();
    }

    public EsgDCLink getDCLink(String dcLinkStr) {
        return dcLinks.get(dcLinkStr);
    }

    public void addDCLink(EsgDCLink dclink) {
        if (dcLinks.containsKey(dclink.toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, DC_LINK, dclink.toString()));
        }
        dcLinks.put(dclink.toString(), dclink);
    }

    public void removeDCLink(String dcLinkStr) {
        if (!lines.containsKey(dcLinkStr)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, DC_LINK, dcLinkStr));
        }
        dcLinks.remove(dcLinkStr);
    }

    public Collection<EsgAcdcVscConverter> getAcdcVscConverters() {
        return vscConverters.values();
    }

    public EsgAcdcVscConverter getAcdcVscConverter(String name) {
        return vscConverters.get(name);
    }

    public void addAcdcVscConverter(EsgAcdcVscConverter vscConverter) {
        if (vscConverters.containsKey(vscConverter.getZnconv().toString())) {
            throw new IllegalArgumentException(String.format(ALREADY_EXISTS_MESSAGE, VSC_CONVERTER_STATION, vscConverter.getZnconv()));
        }
        vscConverters.put(vscConverter.getZnconv().toString(), vscConverter);
    }

    public void removeAcdcVscConverter(String name) {
        if (!vscConverters.containsKey(name)) {
            throw new IllegalArgumentException(String.format(DOES_NOT_EXIST_MESSAGE, VSC_CONVERTER_STATION, name));
        }
        vscConverters.remove(name);
    }

}
