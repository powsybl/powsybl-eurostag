/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.converter;

import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.util.ConnectedComponents;

import java.util.*;
import java.util.stream.StreamSupport;

/**
 *
 * @author pihan
 *
 */
public final class EchUtil {

    private EchUtil() {
    }

    public static final String FAKE_AREA = "FA";

    public static final String FAKE_NODE_NAME1 = "FAKENOD1";

    public static final String FAKE_NODE_NAME2 = "FAKENOD2";

    public static String getBusId(DanglingLine dl) {
        return dl.getId() + "_BUS";
    }

    public static String getLoadId(DanglingLine dl) {
        return dl.getId() + "_LOAD";
    }

    public static Bus getBus(Terminal t, boolean noswitch) {
        if (noswitch) {
            return t.getBusView().getBus();
        } else {
            return t.getBusBreakerView().getBus();
        }
    }

    public static Bus getBus(Terminal t, EurostagEchExportConfig config) {
        return EchUtil.getBus(t, config.isNoSwitch());
    }

    public static Iterable<Bus> getBuses(Network n, EurostagEchExportConfig config) {
        if (config.isNoSwitch()) {
            return n.getBusView().getBuses();
        } else {
            return n.getBusBreakerView().getBuses();
        }
    }

    public static Iterable<Bus> getBuses(VoltageLevel vl, EurostagEchExportConfig config) {
        if (config.isNoSwitch()) {
            return vl.getBusView().getBuses();
        } else {
            return vl.getBusBreakerView().getBuses();
        }
    }

    public static Bus getBus1(VoltageLevel vl, String switchId, EurostagEchExportConfig config) {
        if (config.isNoSwitch()) {
            throw new AssertionError("Should not happen");
        } else {
            return vl.getBusBreakerView().getBus1(switchId);
        }
    }

    public static Bus getBus2(VoltageLevel vl, String switchId, EurostagEchExportConfig config) {
        if (config.isNoSwitch()) {
            throw new AssertionError("Should not happen");
        } else {
            return vl.getBusBreakerView().getBus2(switchId);
        }
    }

    public static Iterable<Switch> getSwitches(VoltageLevel vl, EurostagEchExportConfig config) {
        if (config.isNoSwitch()) {
            return Collections.emptyList();
        } else {
            return vl.getBusBreakerView().getSwitches();
        }
    }

    private static final class DecoratedBus {
        Bus bus;
        int branch = 0;
        int regulatingGenerator = 0;
        float maxP = 0;
        float minP = 0;
        float targetP = 0;

        private DecoratedBus(Bus bus) {
            this.bus = bus;
        }
    }

    private static DecoratedBus decorate(Bus b) {
        final DecoratedBus decoratedBus = new DecoratedBus(b);
        b.visitConnectedEquipments(new DefaultTopologyVisitor() {
            @Override
            public void visitLine(Line line, Line.Side side) {
                decoratedBus.branch++;
            }

            @Override
            public void visitTwoWindingsTransformer(TwoWindingsTransformer transformer, TwoWindingsTransformer.Side side) {
                decoratedBus.branch++;
            }

            @Override
            public void visitThreeWindingsTransformer(ThreeWindingsTransformer transformer, ThreeWindingsTransformer.Side side) {
                decoratedBus.branch++;
            }

            @Override
            public void visitDanglingLine(DanglingLine danglingLine) {
                decoratedBus.branch++;
            }

            @Override
            public void visitGenerator(Generator generator) {
                if (generator.isVoltageRegulatorOn()) {
                    decoratedBus.regulatingGenerator++;
                    decoratedBus.maxP += generator.getMaxP();
                    decoratedBus.minP += generator.getMinP();
                    decoratedBus.targetP += generator.getTargetP();
                }
            }
        });
        return decoratedBus;
    }

    /**
     * Automatically find the best slack bus list
     */
    public static Bus selectSlackbus(Network network, EurostagEchExportConfig config) {
        // avoid buses connected to a switch because of Eurostag LF crash (LU factorisation issue)
        Set<String> busesToAvoid = new HashSet<>();
        for (VoltageLevel vl : network.getVoltageLevels()) {
            for (Switch s : EchUtil.getSwitches(vl, config)) {
                busesToAvoid.add(EchUtil.getBus1(vl, s.getId(), config).getId());
                busesToAvoid.add(EchUtil.getBus2(vl, s.getId(), config).getId());
            }
        }
        Bus bus = selectSlackbusCriteria1(network, config, busesToAvoid);
        if (bus == null) {
            bus = selectSlackbusCriteria1(network, config, Collections.emptySet());
        }
        return bus;
    }

    private static Bus selectSlackbusCriteria1(Network network, EurostagEchExportConfig config, Set<String> busesToAvoid) {
        return StreamSupport.stream(EchUtil.getBuses(network, config).spliterator(), false)
                .sorted(Comparator.comparing(Identifiable::getId))
                .filter(b -> !busesToAvoid.contains(b.getId())
                        && b.getConnectedComponent() != null && b.getConnectedComponent().getNum() == ComponentConstants.MAIN_NUM)
                 .map(EchUtil::decorate)
                 .filter(db -> db.regulatingGenerator > 0 && db.maxP > 100) // only keep bus with a regulating generator and a pmax > 100 MW
                 .sorted((db1, db2) -> Float.compare((db1.maxP - db1.minP) / 2 - db1.targetP, (db2.maxP - db2.minP) / 2 - db2.targetP)) // select first bus with a high margin
                 .limit(1)
                .map(f -> f.bus)
                .findFirst()
                .orElse(null);
    }

    public static boolean isInMainCc(Bus bus) {
        return ConnectedComponents.getCcNum(bus) == ComponentConstants.MAIN_NUM;
    }

    public static boolean isInMainCc(Injection<?> injection, boolean noswitch) {
        return ConnectedComponents.getCcNum(EchUtil.getBus(injection.getTerminal(), noswitch)) == ComponentConstants.MAIN_NUM;
    }

    public static boolean isInMainCc(Branch<?> branch, boolean noswitch) {
        return (ConnectedComponents.getCcNum(EchUtil.getBus(branch.getTerminal1(), noswitch)) == ComponentConstants.MAIN_NUM)
                && (ConnectedComponents.getCcNum(EchUtil.getBus(branch.getTerminal2(), noswitch)) == ComponentConstants.MAIN_NUM);
    }

    /**
     * given an iIDM HVDC line , returns its DC voltage to be used with Eurostag
     * Multiplying  the line's nominalV by 2 corresponds to the fact that iIDM refers to the cable-ground voltage
     * while Eurostag regulations to the cable-cable voltage
     */
    public static double getHvdcLineDcVoltage(HvdcLine line) {
        Objects.requireNonNull(line);
        return line.getNominalV() * 2.0;
    }

    public static boolean isPMode(HvdcConverterStation<?> vscConv, HvdcLine hvdcLine) {
        Objects.requireNonNull(vscConv);
        Objects.requireNonNull(hvdcLine);
        HvdcConverterStation<?> side1Conv = hvdcLine.getConverterStation1();
        HvdcConverterStation<?> side2Conv = hvdcLine.getConverterStation2();
        if ((hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER))
                && (vscConv.getId().equals(side1Conv.getId()))) {
            return true;
        }
        if ((hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER))
                && (vscConv.getId().equals(side2Conv.getId()))) {
            return true;
        }
        return false;
    }

    public static HvdcConverterStation<?> getPStation(HvdcLine hvdcLine) {
        Objects.requireNonNull(hvdcLine);
        if (hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)) {
            return hvdcLine.getConverterStation1();
        }
        if (hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER)) {
            return hvdcLine.getConverterStation2();
        }
        return null;
    }

    public static HvdcConverterStation<?> getVStation(HvdcLine hvdcLine) {
        Objects.requireNonNull(hvdcLine);
        if (hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_RECTIFIER_SIDE_2_INVERTER)) {
            return hvdcLine.getConverterStation2();
        }
        if (hvdcLine.getConvertersMode().equals(HvdcLine.ConvertersMode.SIDE_1_INVERTER_SIDE_2_RECTIFIER)) {
            return hvdcLine.getConverterStation1();
        }
        return null;
    }

    public static boolean isSameConnectionBus(ConnectionBus bus1, ConnectionBus bus2) {
        return (bus1 == null || bus2 == null) ? bus1 == bus2 : (bus1.getId() == null ? bus2.getId() == null : bus1.getId().equals(bus2.getId()));
    }

    public static boolean isSameBus(Bus bus1, Bus bus2) {
        return (bus1 == null || bus2 == null) ? bus1 == bus2 : (bus1.getId() == null ? bus2.getId() == null : bus1.getId().equals(bus2.getId()));
    }

}
