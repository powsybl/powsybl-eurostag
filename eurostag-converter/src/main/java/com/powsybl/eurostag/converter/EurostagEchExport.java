/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.converter;

import com.google.common.base.Strings;
import com.powsybl.commons.PowsyblException;
import com.powsybl.eurostag.model.*;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.extensions.CoordinatedReactiveControl;
import com.powsybl.iidm.network.util.Identifiables;
import com.powsybl.eurostag.model.io.EsgWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class EurostagEchExport implements EurostagEchExporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(EurostagEchExport.class);

    /**
     * epsilon value for conductance
     */
    static final float G_EPSILON = 0.00001f;

    /**
     * epsilon value for susceptance
     */
    static final float B_EPSILON = 0.000001f;

    // FIXME(mathbagu): probably deprecated since Boundary object exists
    private static final String XNODE_V_PROPERTY = "xnode_v";
    private static final String XNODE_ANGLE_PROPERTY = "xnode_angle";

    private static final String SKIPPING_NOT_IN_MAIN_COMPONENT = "not in main component, skipping {}: {}";

    protected final Network network;
    protected final EurostagEchExportConfig config;
    protected final BranchParallelIndexes parallelIndexes;
    protected final EurostagDictionary dictionary;
    protected final EurostagFakeNodes fakeNodes;

    public EurostagEchExport(Network network, EurostagEchExportConfig config, BranchParallelIndexes parallelIndexes, EurostagDictionary dictionary, EurostagFakeNodes fakeNodes) {
        this.network = Objects.requireNonNull(network);
        this.config = Objects.requireNonNull(config);
        this.parallelIndexes = Objects.requireNonNull(parallelIndexes);
        this.dictionary = Objects.requireNonNull(dictionary);
        this.fakeNodes = Objects.requireNonNull(fakeNodes);
    }

    public EurostagEchExport(Network network, EurostagEchExportConfig config) {
        this.network = Objects.requireNonNull(network);
        this.config = config;
        this.fakeNodes = EurostagFakeNodes.build(network, config);
        this.parallelIndexes = BranchParallelIndexes.build(network, config, fakeNodes);
        this.dictionary = EurostagDictionary.create(network, parallelIndexes, config, fakeNodes);
    }

    public EurostagEchExport(Network network) {
        this(network, new EurostagEchExportConfig());
    }

    private void createAreas(EsgNetwork esgNetwork) {
        esgNetwork.addArea(new EsgArea(new Esg2charName(EchUtil.FAKE_AREA), EsgArea.Type.AC));
        for (Country c : network.getCountries()) {
            esgNetwork.addArea(new EsgArea(new Esg2charName(c.toString()), EsgArea.Type.AC));
        }

        if (network.getHvdcLineCount() > 0) {
            esgNetwork.addArea(new EsgArea(new Esg2charName("DC"), EsgArea.Type.DC));
        }
    }

    private EsgNode createNode(String busId, String countryIsoCode, double nominalV, double v, double angle, boolean slackBus) {
        return new EsgNode(new Esg2charName(countryIsoCode),
                new Esg8charName(dictionary.getEsgId(busId)),
                nominalV,
                Double.isNaN(v) ? 1.0 : v / nominalV,
                Double.isNaN(angle) ? 0.0 : angle,
                slackBus);
    }

    private EsgNode createNode(String busId, VoltageLevel vl, double v, double angle, boolean slackBus) {
        String countryCode = vl.getSubstation().getCountry().map(Country::name).orElse(EchUtil.FAKE_AREA);
        return createNode(busId, countryCode, vl.getNominalV(), v, angle, slackBus);
    }

    private void createNodes(EsgNetwork esgNetwork) {
        fakeNodes.referencedEsgIdsAsStream().forEach(esgId -> {
            VoltageLevel vlevel = fakeNodes.getVoltageLevelByEsgId(esgId);
            // FIXME(mathbagu): if vlevel is null, why the nominalV is set 380.0?
            double nominalV = (vlevel != null) ? vlevel.getNominalV() : 380.0;
            esgNetwork.addNode(createNode(esgId, EchUtil.FAKE_AREA, nominalV, nominalV, 0f, false));
        });

        Map<Integer, Bus> sbs = EchUtil.selectSlackbus(network, config);
        if (sbs == null) {
            throw new EsgException("Slack bus not found");
        }
        for (Bus sb : sbs.values()) {
            LOGGER.debug("Slack bus: {} ({})", sb, sb.getVoltageLevel().getId());
        }
        for (Bus b : Identifiables.sort(EchUtil.getBuses(network, config))) {
            // skip buses not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(b)) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "Bus", b.getId());
                continue;
            }
            esgNetwork.addNode(createNode(b.getId(), b.getVoltageLevel(), b.getV(), b.getAngle(), sbs.values().contains(b)));
        }
        for (DanglingLine dl : Identifiables.sort(network.getDanglingLines())) {
            // skip DLs not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(dl, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "DanglingLine", dl.getId());
                continue;
            }
            String strV = dl.getProperty(XNODE_V_PROPERTY);
            String strAngle = dl.getProperty(XNODE_ANGLE_PROPERTY);
            float v = strV != null ? Float.parseFloat(strV) : Float.NaN;
            float angle = strAngle != null ? Float.parseFloat(strAngle) : Float.NaN;
            esgNetwork.addNode(createNode(EchUtil.getBusId(dl), dl.getTerminal().getVoltageLevel(), v, angle, false));
        }
    }

    private static EsgBranchConnectionStatus getStatus(ConnectionBus bus1, ConnectionBus bus2) {
        if (!bus1.isConnected() && !bus2.isConnected()) {
            return EsgBranchConnectionStatus.OPEN_AT_BOTH_SIDES;
        } else if (bus1.isConnected() && bus2.isConnected()) {
            return EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE;
        } else {
            return bus1.isConnected() ? EsgBranchConnectionStatus.OPEN_AT_RECEIVING_SIDE
                    : EsgBranchConnectionStatus.OPEN_AT_SENDING_SIDE;
        }
    }

    private void createCouplingDevices(EsgNetwork esgNetwork) {
        for (VoltageLevel vl : Identifiables.sort(network.getVoltageLevels())) {
            for (Switch sw : Identifiables.sort(EchUtil.getSwitches(vl, config))) {
                Bus bus1 = EchUtil.getBus1(vl, sw.getId(), config);
                Bus bus2 = EchUtil.getBus2(vl, sw.getId(), config);
                //do not export the Switch if bus1==bus2
                if (EchUtil.isSameBus(bus1, bus2)) {
                    LOGGER.warn("skipping Switch: {}; bus1 is equal to bus2: {}", sw.getId(), bus1 != null ? bus1.getId() : bus1);
                    continue;
                }
                // skip switches not in the main connected component
                if (config.isExportMainCCOnly() && (!EchUtil.isInMainCc(bus1) || !EchUtil.isInMainCc(bus2))) {
                    LOGGER.warn("not in main component, skipping Switch: {} {} {}", bus1.getId(), bus2.getId(), sw.getId());
                    continue;
                }

                esgNetwork.addCouplingDevice(new EsgCouplingDevice(new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                        new Esg8charName(dictionary.getEsgId(bus2.getId())),
                        parallelIndexes.getParallelIndex(sw.getId())),
                        sw.isOpen() ? EsgCouplingDevice.ConnectionStatus.OPEN : EsgCouplingDevice.ConnectionStatus.CLOSED));
            }
        }
    }

    private EsgLine createLine(String id, ConnectionBus bus1, ConnectionBus bus2, double nominalV, double r, double x, double g,
                               double b, EsgGeneralParameters parameters) {
        EsgBranchConnectionStatus status = getStatus(bus1, bus2);
        float rate = parameters.getSnref();
        double vnom2 = Math.pow(nominalV, 2);
        double rb = r * parameters.getSnref() / vnom2;
        double rxb = x * parameters.getSnref() / vnom2;
        double gs = g / parameters.getSnref() * vnom2;
        double bs = b / parameters.getSnref() * vnom2;
        return new EsgLine(new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                new Esg8charName(dictionary.getEsgId(bus2.getId())),
                parallelIndexes.getParallelIndex(id)),
                status, rb, rxb, gs, bs, rate);
    }

    private EsgDissymmetricalBranch createDissymmetricalBranch(String id, ConnectionBus bus1, ConnectionBus bus2,
                                                               double nominalV, double r, double x, double g1, double b1, double g2, double b2,
                                                               EsgGeneralParameters parameters) {
        EsgBranchConnectionStatus status = getStatus(bus1, bus2);
        float rate = parameters.getSnref();
        double vnom2 = Math.pow(nominalV, 2);
        double rb = (r * parameters.getSnref()) / vnom2;
        double rxb = (x * parameters.getSnref()) / vnom2;
        double gs1 = (g1 / parameters.getSnref()) * vnom2;
        double bs1 = (b1 / parameters.getSnref()) * vnom2;
        double gs2 = (g2 / parameters.getSnref()) * vnom2;
        double bs2 = (b2 / parameters.getSnref()) * vnom2;
        return new EsgDissymmetricalBranch(new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                new Esg8charName(dictionary.getEsgId(bus2.getId())),
                parallelIndexes.getParallelIndex(id)),
                status, rb, rxb, gs1, bs1, rate, rb, rxb, gs2, bs2);
    }

    private void createLines(EsgNetwork esgNetwork, EsgGeneralParameters parameters) {
        for (Line l : Identifiables.sort(network.getLines())) {
            // skip lines not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(l, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "Line", l.getId());
                continue;
            }
            // It is better to model branches as -normal- lines because it is impossible to open dissymmetrical branches and to do short-circuit on them
            // Therefore, normal lines are created:
            // - If the G and B are the same on each side of the line, even if the G are not 0
            // - If the B are not the same but the G are 0
            // The code could be extended to handle the case where the B are not the same and the G are not the same
            ConnectionBus bus1 = ConnectionBus.fromTerminal(l.getTerminal1(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(l.getTerminal2(), config, fakeNodes);
            //do not export the line if bus1==bus2
            if (EchUtil.isSameConnectionBus(bus1, bus2)) {
                LOGGER.warn("skipping Line: {};  bus1 is equal to bus2: {}", l, bus1 != null ? bus1.getId() : bus1);
                continue;
            }
            if (Math.abs(l.getG1() - l.getG2()) < G_EPSILON
                    && (Math.abs(l.getB1() - l.getB2()) < B_EPSILON
                    || (Math.abs(l.getG1()) < G_EPSILON && Math.abs(l.getG2()) < G_EPSILON))) {
                ConnectionBus bNode = null;
                double b;
                double diffB = 0.0;
                double g = (l.getG1() + l.getG2()) / 2.0;
                double vNom = 0.0;
                if (l.getB1() < l.getB2() - B_EPSILON) {
                    bNode = bus2;
                    b = l.getB1();
                    diffB = l.getB2() - l.getB1();
                    vNom = l.getTerminal2().getVoltageLevel().getNominalV();
                } else if (l.getB2() < l.getB1() - B_EPSILON) {
                    bNode = bus1;
                    b = l.getB2();
                    diffB = l.getB1() - l.getB2();
                    vNom = l.getTerminal1().getVoltageLevel().getNominalV();
                } else {
                    b = (l.getB1() + l.getB2()) / 2.0;
                }

                esgNetwork.addLine(createLine(l.getId(), bus1, bus2, l.getTerminal1().getVoltageLevel().getNominalV(),
                        l.getR(), l.getX(), g, b, parameters));

                if (bNode != null) {
                    //create a dummy shunt attached to bNode
                    String fictionalShuntId = "FKSH" + l.getId();
                    addToDictionary(fictionalShuntId, dictionary, EurostagNamingStrategy.NameType.BANK);

                    int ieleba = 1;
                    double plosba = 0.0;
                    double rcapba = vNom * vNom * diffB;
                    int imaxba = 1;
                    EsgCapacitorOrReactorBank.RegulatingMode xregba = EsgCapacitorOrReactorBank.RegulatingMode.NOT_REGULATING;

                    esgNetwork.addCapacitorsOrReactorBanks(new EsgCapacitorOrReactorBank(new Esg8charName(dictionary.getEsgId(fictionalShuntId)),
                            new Esg8charName(dictionary.getEsgId(bNode.getId())),
                            ieleba, plosba, rcapba, imaxba, xregba));
                }
            } else {
                EsgBranchConnectionStatus status = getStatus(bus1, bus2);
                if (status.equals(EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE)) {
                    // create a dissymmetrical branch
                    esgNetwork.addDissymmetricalBranch(createDissymmetricalBranch(l.getId(), bus1, bus2, l.getTerminal1().getVoltageLevel().getNominalV(),
                            l.getR(), l.getX(), l.getG1(), l.getB1(), l.getG2(), l.getB2(), parameters));
                } else {
                    // half connected dissymmetrical branches are not allowed: remove the dissymmetry (by averaging B1 and B2, G1 and G2) and create a simple line
                    // This is an approximation: the best electrotechnical solution would require an additional fake node and a coupling on each disconnected end of the DyssimmetricalBranch.
                    LOGGER.warn("line {}: half connected dissymmetrical branches are not allowed; removes the dissymmetry by averaging line's B1 {} and B2 {} , G1 {} and  G2 {}", l, l.getB1(), l.getB2(), l.getG1(), l.getG2());
                    esgNetwork.addLine(createLine(l.getId(), bus1, bus2, l.getTerminal1().getVoltageLevel().getNominalV(),
                            l.getR(), l.getX(), (l.getG1() + l.getG2()) / 2, (l.getB1() + l.getB2()) / 2, parameters));
                }
            }
        }
        for (DanglingLine dl : Identifiables.sort(network.getDanglingLines())) {
            // skip if not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(dl, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "DanglingLine", dl.getId());
                continue;
            }
            ConnectionBus bus1 = ConnectionBus.fromTerminal(dl.getTerminal(), config, fakeNodes);
            ConnectionBus bus2 = new ConnectionBus(true, EchUtil.getBusId(dl));
            esgNetwork.addLine(createLine(dl.getId(), bus1, bus2, dl.getTerminal().getVoltageLevel().getNominalV(),
                    dl.getR(), dl.getX(), dl.getG() / 2, dl.getB() / 2, parameters));
        }
    }

    private EsgDetailedTwoWindingTransformer.Tap createTap(TwoWindingsTransformer twt, int iplo, double rho, double dr, double dx,
                                                           double dephas, double rate, EsgGeneralParameters parameters) {
        double nomiU2 = twt.getTerminal2().getVoltageLevel().getNominalV();
        double uno1 = nomiU2 / rho;
        double uno2 = nomiU2;

        //...mTrans.getR() = Get the nominal series resistance specified in Ω at the secondary voltage side.
        double zb2 = Math.pow(nomiU2, 2) / parameters.getSnref();
        double rpu2 = dr / zb2;  //...total line resistance  [p.u.](Base snref)
        double xpu2 = dx / zb2;  //...total line reactance   [p.u.](Base snref)

        //...leakage impedance [%] (base rate)
        double ucc;
        if (xpu2 < 0) {
            ucc = xpu2 * 100 * rate / parameters.getSnref();
        } else {
            double zpu2 = Math.hypot(rpu2, xpu2);
            ucc = zpu2 * 100 * rate / parameters.getSnref();
        }

        return new EsgDetailedTwoWindingTransformer.Tap(iplo, dephas, uno1, uno2, ucc);
    }

    private void createAdditionalBank(EsgNetwork esgNetwork, TwoWindingsTransformer twt, String nodeName, Set<String> additionalBanksIds, double rcapba, double plosba) {
        if ((Math.abs(plosba) > G_EPSILON) || (Math.abs(rcapba) > B_EPSILON)) {
            //simple new bank naming: 4 first letters of the node name, 7th letter of the node name, 'C', 2 digits order code
            String nnodeName = Strings.padEnd(nodeName, 8, ' ');
            String newBankNamePrefix = nnodeName.substring(0, 4) + nnodeName.charAt(6) + 'C';
            String newBankName = newBankNamePrefix + "00";
            int counter = 1;
            while (additionalBanksIds.contains(newBankName)) {
                String newCounter = String.format("%02d", counter++);
                if (newCounter.length() > 2) {
                    throw new EsgException("Renaming error " + nodeName + " -> " + newBankName);
                }
                newBankName = newBankNamePrefix + newCounter;
            }
            additionalBanksIds.add(newBankName);
            LOGGER.info("create additional bank with id: {} at node: {}, for twt: {} ( B={}, G={} ); rcapba={}, plosba={}", newBankName, nodeName, twt, twt.getB(), twt.getG(), rcapba, plosba);
            esgNetwork.addCapacitorsOrReactorBanks(new EsgCapacitorOrReactorBank(new Esg8charName(newBankName), new Esg8charName(nodeName), 1, plosba, rcapba, 1, EsgCapacitorOrReactorBank.RegulatingMode.NOT_REGULATING));
        }
    }

    private double getRtcRho1(TwoWindingsTransformer twt, int p) {
        double rho1 = twt.getRatedU2() / twt.getRatedU1();
        if (twt.getRatioTapChanger() != null) {
            rho1 *= twt.getRatioTapChanger().getStep(p).getRho();
        }
        if (twt.getPhaseTapChanger() != null) {
            rho1 *= twt.getPhaseTapChanger().getCurrentStep().getRho();
        }
        return rho1;
    }

    private double getPtcRho1(TwoWindingsTransformer twt, int p) {
        double rho1 = twt.getRatedU2() / twt.getRatedU1();
        if (twt.getRatioTapChanger() != null) {
            rho1 *= twt.getRatioTapChanger().getCurrentStep().getRho();
        }
        if (twt.getPhaseTapChanger() != null) {
            rho1 *= twt.getPhaseTapChanger().getStep(p).getRho();
        }
        return rho1;
    }

    private double getValue(double initialValue, double rtcStepValue, double ptcStepValue) {
        return initialValue * (1 + rtcStepValue / 100) * (1 + ptcStepValue / 100);
    }

    private double getRtcR(TwoWindingsTransformer twt, int p) {
        return getValue(twt.getR(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getStep(p).getR() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getR() : 0);
    }

    private double getPtcR(TwoWindingsTransformer twt, int p) {
        return getValue(twt.getR(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getCurrentStep().getR() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getStep(p).getR() : 0);
    }

    private double getRtcX(TwoWindingsTransformer twt, int p) {
        return getValue(twt.getX(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getStep(p).getX() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getX() : 0);
    }

    private double getPtcX(TwoWindingsTransformer twt, int p) {
        return getValue(twt.getX(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getCurrentStep().getX() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getStep(p).getX() : 0);
    }

    private double getR(TwoWindingsTransformer twt) {
        return getValue(twt.getR(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getCurrentStep().getR() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getR() : 0);
    }

    private double getG1(TwoWindingsTransformer twt) {
        return getValue(config.isSpecificCompatibility() ? twt.getG() / 2 : twt.getG(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getCurrentStep().getG() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getG() : 0);
    }

    private double getB1(TwoWindingsTransformer twt) {
        return getValue(config.isSpecificCompatibility() ? twt.getB() / 2 : twt.getB(),
                twt.getRatioTapChanger() != null ? twt.getRatioTapChanger().getCurrentStep().getB() : 0,
                twt.getPhaseTapChanger() != null ? twt.getPhaseTapChanger().getCurrentStep().getB() : 0);
    }

    private void createTransformers(EsgNetwork esgNetwork, EsgGeneralParameters parameters) {
        Set<String> additionalBanksIds = new HashSet<>();

        for (TwoWindingsTransformer twt : Identifiables.sort(network.getTwoWindingsTransformers())) {
            // skip transformers not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(twt, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "TwoWindingsTransformer", twt.getId());
                continue;
            }

            ConnectionBus bus1 = ConnectionBus.fromTerminal(twt.getTerminal1(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(twt.getTerminal2(), config, fakeNodes);
            //do not export the Transformer if bus1==bus2
            if (EchUtil.isSameConnectionBus(bus1, bus2)) {
                LOGGER.warn("skipping Transformer: {};  bus1 is equal to bus2: {}", twt, bus1 != null ? bus1.getId() : bus1);
                continue;
            }

            EsgBranchConnectionStatus status = getStatus(bus1, bus2);

            //...IIDM gives no rate value. we take rate = 100 MVA But we have to find the corresponding pcu, pfer ...
            float rate = 100.f;

            //**************************
            //*** LOSSES COMPUTATION *** (Record 1)
            //**************************

            double nomiU2 = twt.getTerminal2().getVoltageLevel().getNominalV();

            //...mTrans.getR() = Get the nominal series resistance specified in Ω at the secondary voltage side.
            double rpu2 = (twt.getR() * parameters.getSnref()) / nomiU2 / nomiU2;  //...total line resistance  [p.u.](Base snref)
            double gpu2 = ((config.isSpecificCompatibility() ? twt.getG() / 2.0 : twt.getG()) / parameters.getSnref()) * nomiU2 * nomiU2;  //...semi shunt conductance [p.u.](Base snref)
            double bpu2 = ((config.isSpecificCompatibility() ? twt.getB() / 2.0 : twt.getB()) / parameters.getSnref()) * nomiU2 * nomiU2;  //...semi shunt susceptance [p.u.](Base snref)
            double gpu2plus = Math.max(0, gpu2);
            double bpu2minus = Math.min(0, bpu2);

            //...changing base snref -> base rate to compute losses
            double pcu = rpu2 * rate * 100.0 / parameters.getSnref();                   //...base rate (100F -> %)
            double pfer = 10000.0 * (gpu2plus / rate) * (parameters.getSnref() / 100.0);  //...base rate
            double modgb = Math.sqrt(Math.pow(gpu2plus, 2) + Math.pow(bpu2minus, 2.0));
            double cmagn = 10000.0 * (modgb / rate) * (parameters.getSnref() / 100.0);    //...magnetizing current [% base rate]
            double esat = 1.0;

            //***************************
            // *** TAP TRANSFORMATION *** (Record 2)
            //***************************

            EsgDetailedTwoWindingTransformer.RegulatingMode regulatingMode = EsgDetailedTwoWindingTransformer.RegulatingMode.NOT_REGULATING;
            Esg8charName zbusr = null; //...regulated node name (if empty, no tap change)
            double voltr = Double.NaN;
            int ktpnom = 1; //...nominal tap number is not available in IIDM. Take th median plot by default
            int ktap8 = 1;  //...initial tap position (tap number) (Ex: 10)
            List<EsgDetailedTwoWindingTransformer.Tap> taps = new ArrayList<>();

            RatioTapChanger rtc = twt.getRatioTapChanger();
            PhaseTapChanger ptc = twt.getPhaseTapChanger();
            if ((rtc != null && ptc == null) || (rtc != null && ptc != null && rtc.isRegulating() && !ptc.isRegulating())) {
                if (rtc != null && ptc != null) {
                    LOGGER.warn("both ptc and rtc exist on two winding transformer {}. Only the rtc is kept because it is regulating.", twt.getId());
                }
                if (rtc.isRegulating()) {
                    ConnectionBus regulatingBus = ConnectionBus.fromTerminal(rtc.getRegulationTerminal(), config, null);
                    if (regulatingBus.getId() != null) {
                        regulatingMode = EsgDetailedTwoWindingTransformer.RegulatingMode.VOLTAGE;
                        zbusr = new Esg8charName(dictionary.getEsgId(regulatingBus.getId()));
                    }
                }
                voltr = rtc.getTargetV();
                ktap8 = rtc.getTapPosition() - rtc.getLowTapPosition() + 1;
                ktpnom = rtc.getStepCount() / 2 + 1;
                for (int p = rtc.getLowTapPosition(); p <= rtc.getHighTapPosition(); p++) {
                    int iplo = p - rtc.getLowTapPosition() + 1;
                    taps.add(createTap(twt, iplo, getRtcRho1(twt, p), getRtcR(twt, p), getRtcX(twt, p), 0.0, rate, parameters));
                }

            } else if (ptc != null || rtc != null) {
                if (rtc != null && ptc != null) {
                    LOGGER.warn("both ptc and rtc exist on two winding transformer {}. Only the ptc is kept.", twt.getId());
                }

                if (ptc.getRegulationMode() == PhaseTapChanger.RegulationMode.CURRENT_LIMITER && ptc.isRegulating()) {
                    String regulbus = EchUtil.getBus(ptc.getRegulationTerminal(), config).getId();
                    if (regulbus.equals(bus1.getId())) {
                        regulatingMode = EsgDetailedTwoWindingTransformer.RegulatingMode.ACTIVE_FLUX_SIDE_1;
                    }
                    if (regulbus.equals(bus2.getId())) {
                        regulatingMode = EsgDetailedTwoWindingTransformer.RegulatingMode.ACTIVE_FLUX_SIDE_2;
                    }
                    if (regulatingMode == EsgDetailedTwoWindingTransformer.RegulatingMode.NOT_REGULATING) {
                        throw new PowsyblException("Phase transformer " + twt.getId() + " has an unknown regulated node");
                    }
                }
                ktap8 = ptc.getTapPosition() - ptc.getLowTapPosition() + 1;
                ktpnom = ptc.getStepCount() / 2 + 1;
                for (int p = ptc.getLowTapPosition(); p <= ptc.getHighTapPosition(); p++) {
                    int iplo = p - ptc.getLowTapPosition() + 1;
                    taps.add(createTap(twt, iplo, getPtcRho1(twt, p), getPtcR(twt, p), getPtcX(twt, p), ptc.getStep(p).getAlpha(), rate, parameters));
                }
            } else if (rtc == null && ptc == null) {
                taps.add(createTap(twt, 1, twt.getRatedU2() / twt.getRatedU1(), twt.getR(), twt.getX(), 0f, rate, parameters));
            }

            // trick to handle the fact that Eurostag model allows only the impedance to change and not the resistance.
            // As an approximation, the resistance is fixed to the value it has for the initial step,
            // but discrepancies will occur if the step is changed.
            if ((ptc != null) || (rtc != null)) {
                double tapAdjustedR = getR(twt);
                double rpu2Adjusted = (tapAdjustedR * parameters.getSnref()) / nomiU2 / nomiU2;
                pcu = rpu2Adjusted * rate * 100.0 / parameters.getSnref();

                double tapAdjustedG = Math.max(getG1(twt), 0);
                double gpu2Adjusted = (tapAdjustedG / parameters.getSnref()) * nomiU2 * nomiU2;
                pfer = 10000.0 * (gpu2Adjusted / rate) * (parameters.getSnref() / 100.0);

                double tapAdjustedB = Math.min(getB1(twt), 0);
                double bpu2Adjusted = (tapAdjustedB / parameters.getSnref()) * nomiU2 * nomiU2;
                modgb = Math.sqrt(Math.pow(gpu2Adjusted, 2) + Math.pow(bpu2Adjusted, 2));
                cmagn = 10000.0 * (modgb / rate) * (parameters.getSnref() / 100.0);
            }

            double pregmin = Double.NaN; //...?
            double pregmax = Double.NaN; //...?

            //handling of the cases where cmagn should be negative and where pfer should be negative
            if ((-twt.getB() < 0) || (twt.getG() < 0) || (config.isSpecificCompatibility())) {
                double rcapba = twt.getB() * nomiU2 * nomiU2 / (config.isSpecificCompatibility() ? 2 : 1);
                double plosba = 1000 * twt.getG() * nomiU2 * nomiU2 / (config.isSpecificCompatibility() ? 2 : 1);
                createAdditionalBank(esgNetwork, twt, dictionary.getEsgId(bus1.getId()), additionalBanksIds, (-twt.getB() < 0) ? rcapba : 0.0, (twt.getG() < 0) ? plosba : 0.0);
                if (config.isSpecificCompatibility()) {
                    //always create a new bank on side2
                    createAdditionalBank(esgNetwork, twt, dictionary.getEsgId(bus2.getId()), additionalBanksIds, rcapba, plosba);
                }
            }

            EsgDetailedTwoWindingTransformer esgTransfo = new EsgDetailedTwoWindingTransformer(
                    new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                            new Esg8charName(dictionary.getEsgId(bus2.getId())),
                            parallelIndexes.getParallelIndex(twt.getId())),
                    status,
                    cmagn,
                    rate,
                    pcu,
                    pfer,
                    esat,
                    ktpnom,
                    ktap8,
                    zbusr,
                    voltr,
                    pregmin,
                    pregmax,
                    regulatingMode);

            //***************************
            // *** TAP TRANSFORMATION *** (Record 3)
            //***************************

            esgTransfo.getTaps().addAll(taps);

            esgNetwork.addDetailedTwoWindingTransformer(esgTransfo);
        }

        for (ThreeWindingsTransformer t3wt : Identifiables.sort(network.getThreeWindingsTransformers())) {
            //TODO: skip transformers not in the main connected component
            /*
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(t3wt, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "TwoWindingsTransformer", t3wt.getId());
                continue;
            }*/

            ConnectionBus bus1 = ConnectionBus.fromTerminal(t3wt.getLeg1().getTerminal(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(t3wt.getLeg2().getTerminal(), config, fakeNodes);
            ConnectionBus bus3 = ConnectionBus.fromTerminal(t3wt.getLeg3().getTerminal(), config, fakeNodes);
            //do not export the Transformer if bus1==bus2
            if (EchUtil.isSameConnectionBus(bus1, bus2)) {
                LOGGER.warn("skipping Transformer: {};  bus1 is equal to bus2: {}", t3wt, bus1 != null ? bus1.getId() : bus1);
                continue;
            }
            if (EchUtil.isSameConnectionBus(bus1, bus3)) {
                LOGGER.warn("skipping Transformer: {};  bus1 is equal to bus3: {}", t3wt, bus1 != null ? bus1.getId() : bus1);
                continue;
            }
            if (EchUtil.isSameConnectionBus(bus2, bus3)) {
                LOGGER.warn("skipping Transformer: {};  bus2 is equal to bus3: {}", t3wt, bus2 != null ? bus2.getId() : bus2);
                continue;
            }

            //TODO: Status
            //EsgBranchConnectionStatus status = getStatus(bus1, bus2);

            //...IIDM gives no rate value. we take rate = 100 MVA But we have to find the corresponding pcu, pfer ...
            float rate = 100.f;

            //**************************
            //*** LOSSES COMPUTATION *** (Record 1)
            //**************************

            double nomiU1 = t3wt.getLeg1().getTerminal().getVoltageLevel().getNominalV();
            double nomiU2 = t3wt.getLeg2().getTerminal().getVoltageLevel().getNominalV();
            double nomiU3 = t3wt.getLeg3().getTerminal().getVoltageLevel().getNominalV();
            double nomiU0 = t3wt.getRatedU0(); // TODO : check OK

            double sNom1 = rate;
            double sNom2 = rate;
            double sNom3 = rate;

            //Leg1 pu values in [p.u.] (base Snom1, nomiU0)
            double r1pu = t3wt.getLeg1().getR() * sNom1 / nomiU0 / nomiU0; //pu in [sNom1, nomiU0]
            double x1pu = t3wt.getLeg1().getX() * sNom1 / nomiU0 / nomiU0; //pu in [sNom1, nomiU0]
            double g1 = config.isSpecificCompatibility() ? t3wt.getLeg1().getG() / parameters.getSnref() / 2.0 : t3wt.getLeg1().getG();
            double b1 = config.isSpecificCompatibility() ? t3wt.getLeg1().getB() / parameters.getSnref()  / 2.0 : t3wt.getLeg1().getB();

            //Leg2 pu values in [p.u.] (base Snom2, nomiU0)
            double r2pu = t3wt.getLeg2().getR() * sNom2 / nomiU0 / nomiU0; //pu in [sNom2, nomiU0]
            double x2pu = t3wt.getLeg2().getX() * sNom2 / nomiU0 / nomiU0; //pu in [sNom2, nomiU0]
            double g2 = config.isSpecificCompatibility() ? t3wt.getLeg2().getG() / parameters.getSnref() / 2.0 : t3wt.getLeg2().getG();  //...semi shunt conductance [p.u.](Base Snom2)
            double b2 = config.isSpecificCompatibility() ? t3wt.getLeg2().getB() / parameters.getSnref()  / 2.0 : t3wt.getLeg2().getB();  //...semi shunt susceptance [p.u.](Base Snom2)

            //Leg3 pu values in [p.u.] (base Snom3, nomiU0)
            double r3 = t3wt.getLeg3().getR(); // in Ohms
            double x3 = t3wt.getLeg3().getX(); // in Ohms
            double g3 = config.isSpecificCompatibility() ? t3wt.getLeg3().getG() / parameters.getSnref() / 2.0 : t3wt.getLeg3().getG(); // in S
            double b3 = config.isSpecificCompatibility() ? t3wt.getLeg3().getB() / parameters.getSnref()  / 2.0 : t3wt.getLeg3().getB(); //in S

            //We make the following approximation supposing that a PowSyBl 3 windings transformer can be represented as follow with a shift of shunt elements y1, y2, y3 to y0:
            //                                      |---(r2+jx2)---(rho2*e(j*alpha2))---2
            //                                      |
            //  1---(rho1*e(j*alpha1))---(r1+jx1)---0
            //                                      |
            //                                      |---(rho3*e(j*alpha3))--+--(r3eq+j*x3eq)---3  with  r3eq = r3 / rho3 / rho3
            //                                                              |
            //                                                              y0 = rho3 * rho3 * [y1 + y2 +y3]
            //                                                              |
            //                                                             ///

            //************
            // yo and z3eq
            //************
            //z3eq is computed moving z3 from left to right of the powsybl leg3 transfo ratio z3eq = z3 / rho3 / rho3 (in Ohms)
            //we use the current rho3 in order to keep consistant LF results
            double rho3 = nomiU0 / nomiU3;
            RatioTapChanger rtcL3 = t3wt.getLeg3().getRatioTapChanger();
            PhaseTapChanger ptcL3 = t3wt.getLeg3().getPhaseTapChanger();
            if (rtcL3 != null) {
                //test
                rho3 = rho3 * rtcL3.getCurrentStep().getRho();

                //System.out.println(" Leg 3 has ratio " + rtcL3.getCurrentStep().getRho());
            }
            if (ptcL3 != null) {
                rho3 = rho3 * ptcL3.getCurrentStep().getRho();
                //System.out.println(" Leg 3 has phase ratio " + ptcL3.getCurrentStep().getRho());
            }

            double r3eq = r3 / rho3 / rho3; // in Ohms on 3-end side
            double x3eq = x3 / rho3 / rho3; // in Ohms on 3-end side
            double r3eqpu = r3eq * sNom3 / nomiU3 / nomiU3; // in pu [Snom3, Vnom3]
            double x3eqpu = x3eq * sNom3 / nomiU3 / nomiU3;

            //**********
            //Pcu
            //**********
            //First compute the Pcu of each leg i = PcuTi
            double pCuT1 = 100 * r1pu; // pcuT1 = copper losses in % of Snom1]
            double pCuT2 = 100 * r2pu;
            double pCuT3 = 100 * r3eqpu;

            double pCu12 = Math.min(sNom1, sNom2) * (pCuT1 / sNom1 + pCuT2 / sNom2);
            double pCu13 = Math.min(sNom1, sNom3) * (pCuT1 / sNom1 + pCuT3 / sNom3);
            double pCu23 = Math.min(sNom2, sNom3) * (pCuT2 / sNom2 + pCuT3 / sNom3);

            //********
            //PFe
            //********
            // In Eurostag modelling, Pfe in legs 1 and 2 are 0, compute Pfe of leg 3 = pFeT3 in [% of Snom3]
            //y0 is obtained moving and summing all y1, y2 and y3 shunt elements on the other side of the leg3 ratio
            double g0 = rho3 * rho3 * (g1 + g2 + g3);
            double b0 = rho3 * rho3 * (b1 + b2 + b3);
            double g0plus = Math.max(0, g0);
            double b0minus = Math.min(0, b0);
            double g0pluspu = g0plus * nomiU3 * nomiU3 / sNom3; // pu [Snom3, Vnom3]
            double b0minuspu = b0minus * nomiU3 * nomiU3 / sNom3; // pu [Snom3, Vnom3]

            // pFe = PFeT3 * Snom3 / min(Snom1, Snom2, Snom3)
            double pFe = 100 * g0pluspu * sNom3 / Math.min(Math.min(sNom2, sNom3), sNom1);

            //********
            // Cmagn
            //********
            //i0T1 = 0 , i0T2 = 0, ioT3 = i0
            double i0T3 = 100 * Math.hypot(b0minuspu, g0pluspu); //100 * parameters.getSnref() / sNom3 * Math.hypot(b0minus, g0pluspu);
            double io = i0T3 * sNom3 / Math.min(Math.min(sNom2, sNom3), sNom1); // is expressed in % pu of the smallest Snom base

            //*******
            // Ucc
            //*******
            // Ucc values depend on the taps of the transformer, simplification hypotheses are needed since EUROSTAG modelling has only one tap changer for all three legs
            // whereas PowSyBl modelling can have both tap ratio and tap phase per leg. The EUROSTAG ratio can only regulate voltage, therefore we will only look regulating ratio taps
            // we settle the following priority order:
            // 1- a regulating ratio tap
            // 2- one fixed tap if nothing is regulating
            RatioTapChanger rtcL1 = t3wt.getLeg1().getRatioTapChanger();
            PhaseTapChanger ptcL1 = t3wt.getLeg1().getPhaseTapChanger();
            RatioTapChanger rtcL2 = t3wt.getLeg2().getRatioTapChanger();
            PhaseTapChanger ptcL2 = t3wt.getLeg2().getPhaseTapChanger();

            EsgThreeWindingTransformer.RegulatingMode regulatingMode = EsgThreeWindingTransformer.RegulatingMode.NOT_REGULATING;
            Esg8charName zbusr = null; //...regulated node name (if empty, no tap change)
            double voltr = Double.NaN;
            int ktpnom = 1; //...nominal tap number is not available in IIDM. Take th median plot by default
            int ktap8 = 1;  //...initial tap position (tap number) (Ex: 10)
            List<EsgThreeWindingTransformer.Tap> taps = new ArrayList<>();

            int rtci = 0;
            int nbTaps = 1; //there is at least one tap
            if (rtcL1 != null && rtcL1.isRegulating()) {
                rtci = 1;
                nbTaps = rtcL1.getStepCount();
                ConnectionBus regulatingBus = ConnectionBus.fromTerminal(rtcL1.getRegulationTerminal(), config, null);
                if (regulatingBus.getId() != null) {
                    regulatingMode = EsgThreeWindingTransformer.RegulatingMode.VOLTAGE;
                    zbusr = new Esg8charName(dictionary.getEsgId(regulatingBus.getId()));
                    voltr = rtcL1.getTargetV();
                    ktap8 = rtcL1.getTapPosition() - rtcL1.getLowTapPosition() + 1;
                    ktpnom = rtcL1.getStepCount() / 2 + 1;
                }
            } else if (rtcL2 != null && rtcL2.isRegulating()) {
                rtci = 2;
                nbTaps = rtcL2.getStepCount();
                ConnectionBus regulatingBus = ConnectionBus.fromTerminal(rtcL2.getRegulationTerminal(), config, null);
                if (regulatingBus.getId() != null) {
                    regulatingMode = EsgThreeWindingTransformer.RegulatingMode.VOLTAGE;
                    zbusr = new Esg8charName(dictionary.getEsgId(regulatingBus.getId()));
                    voltr = rtcL2.getTargetV();
                    ktap8 = rtcL2.getTapPosition() - rtcL2.getLowTapPosition() + 1;
                    ktpnom = rtcL2.getStepCount() / 2 + 1;
                }
            } else if (rtcL3 != null && rtcL3.isRegulating()) {
                rtci = 3;
                nbTaps = rtcL3.getStepCount();
                ConnectionBus regulatingBus = ConnectionBus.fromTerminal(rtcL3.getRegulationTerminal(), config, null);
                if (regulatingBus.getId() != null) {
                    regulatingMode = EsgThreeWindingTransformer.RegulatingMode.VOLTAGE;
                    zbusr = new Esg8charName(dictionary.getEsgId(regulatingBus.getId()));
                    voltr = rtcL3.getTargetV();
                    ktap8 = rtcL3.getTapPosition() - rtcL3.getLowTapPosition() + 1;
                    ktpnom = rtcL3.getStepCount() / 2 + 1;
                }
            }

            double[] ucc12 = new double[nbTaps];
            double[] ucc13 = new double[nbTaps];
            double[] ucc23 = new double[nbTaps];

            double[] uno1 = new double[nbTaps];
            double[] uno2 = new double[nbTaps];
            double[] uno3 = new double[nbTaps];

            for (int i = 0; i < nbTaps; i++) {

                uno1[i] = nomiU1;
                uno2[i] = nomiU2;
                uno3[i] = nomiU3;

                //initialization with the current tap
                if (ptcL1 != null) {
                    uno1[i] = uno1[i] / ptcL1.getCurrentStep().getRho();
                }
                if (ptcL2 != null) {
                    uno2[i] = uno2[i] / ptcL2.getCurrentStep().getRho();
                }
                if (ptcL3 != null) {
                    uno3[i] = uno3[i] / ptcL3.getCurrentStep().getRho();
                }

                double uccT1 = 100 * Math.hypot(r1pu, x1pu);
                if (x1pu < 0) {
                    uccT1 = x1pu * 100;
                }
                double uccT2 = 100 * Math.hypot(r2pu, x2pu);
                if (x2pu < 0) {
                    uccT2 = x2pu * 100;
                }
                double uccT3 = 100 * Math.hypot(r3eqpu, x3eqpu);
                if (x3eqpu < 0) {
                    uccT3 = x3eqpu * 100;
                }
                if (rtci == 1) {
                    double r1putap = getValue(r1pu, rtcL1.getStep(rtcL1.getLowTapPosition() + i).getR(), 0);
                    double x1putap = getValue(x1pu, rtcL1.getStep(rtcL1.getLowTapPosition() + i).getX(), 0);
                    uccT1 = 100 * Math.hypot(r1putap, x1putap);
                    uno1[i] = uno1[i] / rtcL1.getStep(rtcL1.getLowTapPosition() + i).getRho();
                    if (rtcL2 != null) {
                        uno2[i] = uno2[i] / rtcL2.getCurrentStep().getRho();
                    }
                    if (rtcL3 != null) {
                        uno3[i] = uno3[i] / rtcL3.getCurrentStep().getRho();
                    }
                } else if (rtci == 2) {
                    double r2putap = getValue(r2pu, rtcL2.getStep(rtcL2.getLowTapPosition() + i).getR(), 0);
                    double x2putap = getValue(x2pu, rtcL2.getStep(rtcL2.getLowTapPosition() + i).getX(), 0);
                    uccT2 = 100 * Math.hypot(r2putap, x2putap);
                    if (rtcL1 != null) {
                        uno1[i] = uno1[i] / rtcL1.getCurrentStep().getRho();
                    }
                    if (rtcL3 != null) {
                        uno3[i] = uno3[i] / rtcL3.getCurrentStep().getRho();
                    }
                } else if (rtci == 3) {
                    double r3putap = getValue(r3eqpu, rtcL3.getStep(rtcL3.getLowTapPosition() + i).getR(), 0);
                    double x3putap = getValue(x3eqpu, rtcL3.getStep(rtcL3.getLowTapPosition() + i).getX(), 0);
                    uccT3 = 100 * Math.hypot(r3putap, x3putap);
                    if (rtcL1 != null) {
                        uno1[i] = uno1[i] / rtcL1.getCurrentStep().getRho();
                    }
                    if (rtcL2 != null) {
                        uno2[i] = uno2[i] / rtcL2.getCurrentStep().getRho();
                    }
                } else {
                    if (rtcL1 != null) {
                        uno1[i] = uno1[i] / rtcL1.getCurrentStep().getRho();
                    }
                    if (rtcL2 != null) {
                        uno2[i] = uno2[i] / rtcL2.getCurrentStep().getRho();
                    }
                    if (rtcL3 != null) {
                        uno3[i] = uno3[i] / rtcL3.getCurrentStep().getRho();
                    }
                }

                ucc12[i] = Math.min(sNom1, sNom2) * (uccT1 / sNom1 + uccT2 / sNom2);
                ucc13[i] = Math.min(sNom1, sNom3) * (uccT1 / sNom1 + uccT3 / sNom3);
                ucc23[i] = Math.min(sNom2, sNom3) * (uccT2 / sNom2 + uccT3 / sNom3);

                //TODO: phase shift per winding
                //by default it is 0

                if (nbTaps == 1) {
                    //create at least 2 EUROSTAG taps
                    taps.add(new EsgThreeWindingTransformer.Tap(1, 0., 0., 0., uno1[i], uno2[i], uno3[i], ucc12[i], ucc13[i], ucc23[i]));
                    taps.add(new EsgThreeWindingTransformer.Tap(2, 0., 0., 0., uno1[i], uno2[i], uno3[i], ucc12[i], ucc13[i], ucc23[i]));
                } else {
                    //create one tap per i
                    taps.add(new EsgThreeWindingTransformer.Tap(i + 1, 0., 0., 0., uno1[i], uno2[i], uno3[i], ucc12[i], ucc13[i], ucc23[i]));
                }

            }

            EsgThreeWindingTransformer.EsgT3WConnectionStatus status = EsgThreeWindingTransformer.EsgT3WConnectionStatus.CLOSED_AT_ALL_SIDES; //TODO : check connections
            double esat = 1.0;

            //add T3E to the network
            EsgThreeWindingTransformer esgT3WTransfo = new EsgThreeWindingTransformer(
                    new EsgThreeWindingTransformer.EsgT3WName(new Esg8charName(dictionary.getEsgId(t3wt.getId())),
                            new Esg8charName(dictionary.getEsgId(bus1.getId())),
                            new Esg8charName(dictionary.getEsgId(bus2.getId())),
                            new Esg8charName(dictionary.getEsgId(bus3.getId()))),
                    status,
                    io,
                    sNom1,
                    sNom2,
                    sNom3,
                    pCu12,
                    pCu13,
                    pCu23,
                    pFe,
                    esat,
                    ktpnom,
                    ktap8,
                    zbusr,
                    voltr,
                    regulatingMode);

            //***************************
            // *** TAP TRANSFORMATION *** (Record 3)
            //***************************
            esgT3WTransfo.getTaps().addAll(taps);

            esgNetwork.addThreeWindingTransformer(esgT3WTransfo);

        }
    }

    private EsgLoad createLoad(ConnectionBus bus, String loadId, double p0, double q0) {
        EsgConnectionStatus status = bus.isConnected() ? EsgConnectionStatus.CONNECTED : EsgConnectionStatus.NOT_CONNECTED;
        return new EsgLoad(status, new Esg8charName(dictionary.getEsgId(loadId)),
                new Esg8charName(dictionary.getEsgId(bus.getId())),
                0f, 0f, p0, 0f, 0f, q0);
    }

    private void createLoads(EsgNetwork esgNetwork) {
        for (Load l : Identifiables.sort(network.getLoads())) {
            // skip loads not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(l, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "Load", l.getId());
                continue;
            }
            ConnectionBus bus = ConnectionBus.fromTerminal(l.getTerminal(), config, fakeNodes);
            esgNetwork.addLoad(createLoad(bus, l.getId(), l.getP0(), l.getQ0()));
        }
        for (DanglingLine dl : Identifiables.sort(network.getDanglingLines())) {
            // skip dls not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(dl, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "DanglingLine", dl.getId());
                continue;
            }
            ConnectionBus bus = new ConnectionBus(true, EchUtil.getBusId(dl));
            esgNetwork.addLoad(createLoad(bus, EchUtil.getLoadId(dl), dl.getP0(), dl.getQ0()));
        }
    }

    private void createGenerators(EsgNetwork esgNetwork) {
        for (Generator g : Identifiables.sort(network.getGenerators())) {
            // skip generators not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(g, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "Generator", g.getId());
                continue;
            }

            ConnectionBus bus = ConnectionBus.fromTerminal(g.getTerminal(), config, fakeNodes);

            EsgConnectionStatus status = bus.isConnected() ? EsgConnectionStatus.CONNECTED : EsgConnectionStatus.NOT_CONNECTED;
            double pgen = g.getTargetP();
            double qgen = g.getTargetQ();
            double pgmin = g.getMinP();
            double pgmax = g.getMaxP();
            boolean isQminQmaxInverted = g.getReactiveLimits().getMinQ(pgen) > g.getReactiveLimits().getMaxQ(pgen);
            if (isQminQmaxInverted) {
                LOGGER.warn("inverted qmin {} and qmax {} values for generator {}", g.getReactiveLimits().getMinQ(pgen), g.getReactiveLimits().getMaxQ(pgen), g.getId());
                qgen = -g.getTerminal().getQ();
            }
            boolean isVoltageRegulatorOn = g.isVoltageRegulatorOn();
            // Exception for out of bound regulating generators
            if (config.isSpecificCompatibility() && (g.getTargetP() < 0.0001) && (g.getMinP() > 0.0001)) {
                isVoltageRegulatorOn = false;
                LOGGER.warn("out of bound regulating generator {}, targetP {}, minP {} : turn off its voltage regulation", g.getId(), g.getTargetP(), g.getMinP());
            }
            // in case qmin and qmax are inverted, take out the unit from the voltage regulation if it has a target Q
            // and open widely the Q interval
            double qgmin = (config.isNoGeneratorMinMaxQ() || isQminQmaxInverted) ? -9999 : g.getReactiveLimits().getMinQ(pgen);
            double qgmax = (config.isNoGeneratorMinMaxQ() || isQminQmaxInverted) ? 9999 : g.getReactiveLimits().getMaxQ(pgen);
            EsgRegulatingMode mode = (isQminQmaxInverted && !Double.isNaN(qgen)) ? EsgRegulatingMode.NOT_REGULATING :
                    (isVoltageRegulatorOn && g.getTargetV() >= 0.1 ? EsgRegulatingMode.REGULATING : EsgRegulatingMode.NOT_REGULATING);
            double vregge = (isQminQmaxInverted && !Double.isNaN(qgen)) ? Double.NaN : (isVoltageRegulatorOn ? g.getTargetV() : Double.NaN);
            float qgensh = 1.f;
            if (g.getExtension(CoordinatedReactiveControl.class) != null) {
                qgensh = (float) (g.getExtension(CoordinatedReactiveControl.class).getQPercent() / 100.);
            }

            //fails, when noSwitch is true !!
            //Bus regulatingBus = g.getRegulatingTerminal().getBusBreakerView().getConnectableBus();
            ConnectionBus regulatingBus = ConnectionBus.fromTerminal(g.getRegulatingTerminal(), config, fakeNodes);

            esgNetwork.addGenerator(new EsgGenerator(new Esg8charName(dictionary.getEsgId(g.getId())),
                    new Esg8charName(dictionary.getEsgId(bus.getId())),
                    pgmin, pgen, pgmax, qgmin, qgen, qgmax, mode, vregge,
                    new Esg8charName(dictionary.getEsgId(regulatingBus.getId())),
                    qgensh, status));
        }
    }

    private void createBanks(EsgNetwork esgNetwork) {
        for (ShuntCompensator sc : Identifiables.sort(network.getShuntCompensators())) {
            // skip shunts not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(sc, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "ShuntCompensator", sc.getId());
                continue;
            }

            if (sc.getModelType() == ShuntCompensatorModelType.LINEAR) {
                createEsgCapacitorOrReactorBank(esgNetwork, sc);
            } else if (sc.getModelType() == ShuntCompensatorModelType.NON_LINEAR) {
                throw new EsgException("TODO: Non linear shunt compensator are not supported");
            } else {
                throw new AssertionError("Unsupported shunt compensator type: " + sc.getModelType());
            }
        }
    }

    private void createEsgCapacitorOrReactorBank(EsgNetwork esgNetwork, ShuntCompensator sc) {
        ShuntCompensatorLinearModel model = sc.getModel(ShuntCompensatorLinearModel.class);
        ConnectionBus bus = ConnectionBus.fromTerminal(sc.getTerminal(), config, fakeNodes);

        //...number of steps in service
        int ieleba = bus.isConnected() ? sc.getSectionCount() : 0; // not really correct, because it can be connected with zero section, EUROSTAG should be modified...
        double vnom = sc.getTerminal().getVoltageLevel().getNominalV();
        double plosba = 1000 * vnom * vnom * 0.; // no active lost in the iidm shunt compensator. Expressed in kw
        double rcapba = vnom * vnom * model.getBPerSection();
        int imaxba = sc.getMaximumSectionCount();
        EsgCapacitorOrReactorBank.RegulatingMode xregba = EsgCapacitorOrReactorBank.RegulatingMode.NOT_REGULATING;
        esgNetwork.addCapacitorsOrReactorBanks(new EsgCapacitorOrReactorBank(new Esg8charName(dictionary.getEsgId(sc.getId())),
                new Esg8charName(dictionary.getEsgId(bus.getId())),
                ieleba, plosba, rcapba, imaxba, xregba));
    }

    private void createStaticVarCompensators(EsgNetwork esgNetwork) {
        for (StaticVarCompensator svc : Identifiables.sort(network.getStaticVarCompensators())) {
            // skip SVCs not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(svc, config.isNoSwitch())) {
                LOGGER.warn(SKIPPING_NOT_IN_MAIN_COMPONENT, "StaticVarCompensator", svc.getId());
                continue;
            }
            ConnectionBus bus = ConnectionBus.fromTerminal(svc.getTerminal(), config, fakeNodes);

            Esg8charName znamsvc = new Esg8charName(dictionary.getEsgId(svc.getId()));
            EsgConnectionStatus xsvcst = bus.isConnected() ? EsgConnectionStatus.CONNECTED : EsgConnectionStatus.NOT_CONNECTED;
            Esg8charName znodsvc = new Esg8charName(dictionary.getEsgId(bus.getId()));
            double vlNomVoltage = svc.getTerminal().getVoltageLevel().getNominalV();
            double factor = (float) Math.pow(vlNomVoltage, 2);
            double bmin = (!config.isSvcAsFixedInjectionInLF()) ? svc.getBmin() * factor : -9999999; // [Mvar]
            double binit; // [Mvar]
            if (!config.isSvcAsFixedInjectionInLF()) {
                binit = svc.getReactivePowerSetpoint();
            } else {
                binit = svc.getTerminal().getQ();
                Bus svcBus = EchUtil.getBus(svc.getTerminal(), config);
                if ((svcBus != null) && (Math.abs(svcBus.getV()) > 0.0)) {
                    binit = binit * Math.pow(vlNomVoltage / svcBus.getV(), 2);
                }
            }
            double bmax = (!config.isSvcAsFixedInjectionInLF()) ? svc.getBmax() * factor : 9999999; // [Mvar]
            EsgRegulatingMode xregsvc = ((svc.getRegulationMode() == StaticVarCompensator.RegulationMode.VOLTAGE) && (!config.isSvcAsFixedInjectionInLF())) ? EsgRegulatingMode.REGULATING : EsgRegulatingMode.NOT_REGULATING;
            double vregsvc = svc.getVoltageSetpoint();
            double qsvsch = 1.0; //TODO: extend CoordinatedReactiveControl to static var compensator.
            esgNetwork.addStaticVarCompensator(
                    new EsgStaticVarCompensator(znamsvc, xsvcst, znodsvc, bmin, binit, bmax, xregsvc, vregsvc, qsvsch));
        }
    }

    //add a new couple (iidmId, esgId). EsgId is built from iidmId using a simple cut-name mapping strategy
    private String addToDictionary(String iidmId, EurostagDictionary dictionary, EurostagNamingStrategy.NameType nameType) {
        if (dictionary.iidmIdExists(iidmId)) {
            throw new EsgException("iidmId " + iidmId + " already exists in dictionary");
        }
        String esgId = iidmId.length() > nameType.getLength() ? iidmId.substring(0, nameType.getLength())
                : Strings.padEnd(iidmId, nameType.getLength(), ' ');
        int counter = 0;
        while (dictionary.esgIdExists(esgId)) {
            String counterStr = Integer.toString(counter++);
            if (counterStr.length() > nameType.getLength()) {
                throw new EsgException("Renaming fatal error " + iidmId + " -> " + esgId);
            }
            esgId = esgId.substring(0, nameType.getLength() - counterStr.length()) + counterStr;
        }
        dictionary.add(iidmId, esgId);
        return esgId;
    }

    protected double zeroIfNanOrValue(double value) {
        return Double.isNaN(value) ? 0 : value;
    }

    protected EsgAcdcVscConverter createAcdcVscConverter(VscConverterStation vscConv, HvdcLine hline, Esg8charName vscConvDcName) {
        Objects.requireNonNull(vscConv);
        Objects.requireNonNull(hline, "no hvdc line connected to VscConverterStation " + vscConv.getId());
        boolean isPmode = EchUtil.isPMode(vscConv, hline);
        Esg8charName znamsvc = new Esg8charName(dictionary.getEsgId(vscConv.getId())); // converter station ID
        Esg8charName receivingNodeDcName = new Esg8charName("GROUND"); // receiving DC node name; always GROUND
        ConnectionBus vscConvBus = ConnectionBus.fromTerminal(vscConv.getTerminal(), config, fakeNodes);
        Esg8charName acNode = dictionary.iidmIdExists(vscConvBus.getId()) ? new Esg8charName(dictionary.getEsgId(vscConvBus.getId()))
                : null;
        if (acNode == null) {
            throw new EsgException("VSCConverter " + vscConv.getId() + " : acNode mapping not found");
        }
        EsgAcdcVscConverter.ConverterState xstate = EsgAcdcVscConverter.ConverterState.ON; // converter state ' ' ON; 'S' OFF - always ON, even when the bus is disconnected?
        EsgAcdcVscConverter.DCControlMode xregl = isPmode ? EsgAcdcVscConverter.DCControlMode.AC_ACTIVE_POWER : EsgAcdcVscConverter.DCControlMode.DC_VOLTAGE; // DC control mode 'P' AC_ACTIVE_POWER; 'V' DC_VOLTAGE
        //AC control mode assumed to be "AC reactive power"(Q)
        EsgAcdcVscConverter.ACControlMode xoper = EsgAcdcVscConverter.ACControlMode.AC_REACTIVE_POWER; // AC control mode 'V' AC_VOLTAGE; 'Q' AC_REACTIVE_POWER; 'A' AC_POWER_FACTOR
        float rrdc = 0; // resistance [Ohms]
        float rxdc = 16; // reactance [Ohms]

        double activeSetPoint = zeroIfNanOrValue(hline.getActivePowerSetpoint()); // AC active power setpoint [MW]. Only if DC control mode is 'P'
        //subtracts losses on the P side (even if the station in context is V)
        double pac = activeSetPoint - Math.abs(activeSetPoint * EchUtil.getPStation(hline).getLossFactor() / 100.0);
        pac = isPmode ? pac : -pac; //change sign in case of V mode side
        // multiplying  the line's nominalV by 2 corresponds to the fact that iIDM refers to the cable-ground voltage
        // while Eurostag regulations to the cable-cable voltage
        double pvd = EchUtil.getHvdcLineDcVoltage(hline); // DC voltage setpoint [MW]. Only if DC control mode is 'V'
        double pre = -vscConv.getReactivePowerSetpoint(); // AC reactive power setpoint [Mvar]. Only if AC control mode is 'Q'
        if ((Double.isNaN(pre)) || (vscConv.isVoltageRegulatorOn())) {
            double terminalQ = vscConv.getTerminal().getQ();
            if (Double.isNaN(terminalQ)) {
                pre = zeroIfNanOrValue(pre);
            } else {
                pre = terminalQ;
            }
        }
        float pco = Float.NaN; // AC power factor setpoint. Only if AC control mode is 'A'
        float qvscsh = 1; // Reactive sharing cofficient [%]. Only if AC control mode is 'V'
        double pvscmin = -hline.getMaxP(); // Minimum AC active power [MW]
        double pvscmax = hline.getMaxP(); // Maximum AC active power [MW]
        double qvscmin = vscConv.getReactiveLimits().getMinQ(0); // Minimum reactive power injected on AC node [kV]
        double qvscmax = vscConv.getReactiveLimits().getMaxQ(0); // Maximum reactive power injected on AC node [kV]
        // iIDM vscConv.getLossFactor() is in % of the MW. As it is, not suitable for vsb0, which is fixed in MW
        // for now, set  vsb0, vsb1,vsb2 to 0
        float vsb0 = 0; // Losses coefficient Beta0 [MW]
        float vsb1 = 0; // Losses coefficient Beta1 [kW]
        float vsb2 = 0; // Losses coefficient Beta2 [Ohms]

        Bus connectedBus = vscConv.getTerminal().getBusBreakerView().getConnectableBus();
        if (connectedBus == null) {
            connectedBus = vscConv.getTerminal().getBusView().getConnectableBus();
            if (connectedBus == null) {
                throw new EsgException("VSCConverter " + vscConv.getId() + " : connected bus not found!");
            }
        }
        double mvm = connectedBus.getV() / connectedBus.getVoltageLevel().getNominalV(); // Initial AC modulated voltage magnitude [p.u.]
        double mva = connectedBus.getAngle(); // Initial AC modulated voltage angle [deg]
        double pva = connectedBus.getV(); // AC voltage setpoint [kV]. Only if AC control mode is 'V'

        return new EsgAcdcVscConverter(
                znamsvc,
                vscConvDcName,
                receivingNodeDcName,
                acNode,
                xstate,
                xregl,
                xoper,
                rrdc,
                rxdc,
                pac,
                pvd,
                pva,
                pre,
                pco,
                qvscsh,
                pvscmin,
                pvscmax,
                qvscmin,
                qvscmax,
                vsb0,
                vsb1,
                vsb2,
                mvm,
                mva);
    }

    protected double computeLosses(HvdcLine hvdcLine, HvdcConverterStation<?> convStation, double activeSetPoint) {
        double cableLossesEnd = EchUtil.isPMode(convStation, hvdcLine) ? 0.0 : 1.0;
        //Eurostag model requires a fixed resistance of 1 ohm at 640 kV equivalent to 0.25 ohm at 320 kV
        return Math.abs(activeSetPoint * convStation.getLossFactor() / 100.0) + cableLossesEnd * (hvdcLine.getR() - 0.25) * Math.pow(activeSetPoint / hvdcLine.getNominalV(), 2);
    }

    protected double computeLosses(HvdcLine hvdcLine, HvdcConverterStation<?> convStation) {
        double activeSetPoint = zeroIfNanOrValue(hvdcLine.getActivePowerSetpoint());
        return computeLosses(hvdcLine, convStation, activeSetPoint);
    }

    private EsgLoad createConverterStationAdditionalLoad(HvdcLine hvdcLine, HvdcConverterStation<?> convStation) {
        double ploss = computeLosses(hvdcLine, convStation);
        ConnectionBus rectConvBus = ConnectionBus.fromTerminal(convStation.getTerminal(), config, fakeNodes);
        String fictionalLoadId = "fict_" + convStation.getId();
        addToDictionary(fictionalLoadId, dictionary, EurostagNamingStrategy.NameType.LOAD);
        return createLoad(rectConvBus, fictionalLoadId, ploss, 0);
    }

    private void createAcdcVscConverters(EsgNetwork esgNetwork) {
        //creates 2 DC nodes, for each hvdc line (one node per converter station)
        for (HvdcLine hvdcLine : Identifiables.sort(network.getHvdcLines())) {
            // skip lines with converter stations not in the main connected component
            if (config.isExportMainCCOnly() && (!EchUtil.isInMainCc(hvdcLine.getConverterStation1(), config.isNoSwitch()) || !EchUtil.isInMainCc(hvdcLine.getConverterStation2(), config.isNoSwitch()))) {
                LOGGER.warn("skipped HVDC line {}: at least one converter station is not in main component", hvdcLine.getId());
                continue;
            }
            HvdcConverterStation<?> convStation1 = hvdcLine.getConverterStation1();
            HvdcConverterStation<?> convStation2 = hvdcLine.getConverterStation2();

            if (convStation1.getHvdcType() == HvdcConverterStation.HvdcType.LCC || convStation2.getHvdcType() == HvdcConverterStation.HvdcType.LCC) {
                throw new UnsupportedOperationException("Conversion of LCC is not supported yet");
            }

            //create two dc nodes, one for each conv. station
            Esg8charName hvdcNodeName1 = new Esg8charName(addToDictionary("DC_" + convStation1.getId(), dictionary, EurostagNamingStrategy.NameType.NODE));
            Esg8charName hvdcNodeName2 = new Esg8charName(addToDictionary("DC_" + convStation2.getId(), dictionary, EurostagNamingStrategy.NameType.NODE));
            double dcVoltage = EchUtil.getHvdcLineDcVoltage(hvdcLine);
            esgNetwork.addDCNode(new EsgDCNode(new Esg2charName("DC"), hvdcNodeName1, dcVoltage, 1));
            esgNetwork.addDCNode(new EsgDCNode(new Esg2charName("DC"), hvdcNodeName2, dcVoltage, 1));

            //create a dc link, representing the hvdc line
            //Eurostag model requires a resistance of 1 ohm (not hvdcLine.getR())
            float r = 1.0f;
            esgNetwork.addDCLink(new EsgDCLink(hvdcNodeName1, hvdcNodeName2, '1', r, EsgDCLink.LinkStatus.ON));

            //create the two converter stations
            EsgAcdcVscConverter esgConv1 = createAcdcVscConverter(network.getVscConverterStation(convStation1.getId()), hvdcLine, hvdcNodeName1);
            EsgAcdcVscConverter esgConv2 = createAcdcVscConverter(network.getVscConverterStation(convStation2.getId()), hvdcLine, hvdcNodeName2);
            esgNetwork.addAcdcVscConverter(esgConv1);
            esgNetwork.addAcdcVscConverter(esgConv2);

            //Create one load on the node to which converters stations are connected
            esgNetwork.addLoad(createConverterStationAdditionalLoad(hvdcLine, convStation1));
            esgNetwork.addLoad(createConverterStationAdditionalLoad(hvdcLine, convStation2));
        }
    }

    @Override
    public EsgNetwork createNetwork(EsgGeneralParameters parameters) {

        EsgNetwork esgNetwork = new EsgNetwork();

        // areas
        createAreas(esgNetwork);

        // coupling devices
        createCouplingDevices(esgNetwork);

        // lines
        createLines(esgNetwork, parameters);

        // transformers
        createTransformers(esgNetwork, parameters);

        // loads
        createLoads(esgNetwork);

        // generators
        createGenerators(esgNetwork);

        // shunts
        createBanks(esgNetwork);

        // static VAR compensators
        createStaticVarCompensators(esgNetwork);

        // ACDC VSC Converters
        createAcdcVscConverters(esgNetwork);

        // nodes
        createNodes(esgNetwork);

        return esgNetwork;
    }

    private EsgSpecialParameters createEsgSpecialParameters(EurostagEchExportConfig config) {
        return config.isSpecificCompatibility() ? null : new EsgSpecialParameters();
    }

    public void write(Writer writer, EsgGeneralParameters parameters, EsgSpecialParameters specialParameters) throws IOException {
        EsgNetwork esgNetwork = createNetwork(parameters);
        new EsgWriter(esgNetwork, parameters, specialParameters).write(writer, network.getId() + "/" + network.getVariantManager().getWorkingVariantId());
    }

    public void write(Writer writer) throws IOException {
        write(writer, new EsgGeneralParameters(), createEsgSpecialParameters(config));
    }

    public void write(Path file, EsgGeneralParameters parameters, EsgSpecialParameters specialParameters) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(writer, parameters, specialParameters);
        }
    }

    public void write(Path file) throws IOException {
        write(file, new EsgGeneralParameters(), createEsgSpecialParameters(config));
    }

}
