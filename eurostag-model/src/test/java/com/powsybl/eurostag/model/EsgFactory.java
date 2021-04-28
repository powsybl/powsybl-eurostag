/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

/**
 * @author Mathieu BAGUE {@literal <mathieu.bague at rte-france.com>}
 */
public final class EsgFactory {

    private static final Esg2charName FR = new Esg2charName("FR");
    private static final Esg2charName FA = new Esg2charName("FA");
    private static final Esg2charName DC = new Esg2charName("DC");

    private static final Esg8charName NLOAD = new Esg8charName("NLOAD");
    private static final Esg8charName NGEN = new Esg8charName("NGEN");
    private static final Esg8charName NHV1 = new Esg8charName("NHV1");
    private static final Esg8charName NHV2 = new Esg8charName("NHV2");

    private static final Esg8charName B1 = new Esg8charName("B1");
    private static final Esg8charName VL2_0 = new Esg8charName("VL2_0");
    private static final Esg8charName VL2_2 = new Esg8charName("VL2_2");

    private static final Esg8charName DC_C1 = new Esg8charName("DC_C1");
    private static final Esg8charName DC_C2 = new Esg8charName("DC_C2");
    private static final Esg8charName GROUND = new Esg8charName("GROUND");

    private EsgFactory() {
    }

    public static EsgNetwork create() {
        EsgNetwork network = new EsgNetwork();

        // Create areas
        network.addArea(new EsgArea(FA, EsgArea.Type.AC));
        network.addArea(new EsgArea(FR, EsgArea.Type.AC));

        // Create nodes
        network.addNode(new EsgNode(FA, name("FAKENOD1"), 380, 1, 0, false));
        network.addNode(new EsgNode(FA, name("FAKENOD2"), 380, 1, 0, false));
        network.addNode(new EsgNode(FR, NGEN, 24, 1, 0, true));
        network.addNode(new EsgNode(FR, NHV1, 380, 1, 0, false));
        network.addNode(new EsgNode(FR, NHV2, 380, 1, 0, false));
        network.addNode(new EsgNode(FR, NLOAD, 150, 1, 0, false));

        // Create lines
        network.addLine(new EsgLine(new EsgBranchName(NHV1, NHV2, '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE, 0.002078, 0.022853, 0, 0.278692, 100));
        network.addLine(new EsgLine(new EsgBranchName(NHV1, NHV2, '2'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE, 0.002078, 0.022853, 0, 0.278692, 100));

        // Create transformers
        EsgDetailedTwoWindingTransformer twt1 = new EsgDetailedTwoWindingTransformer(new EsgBranchName(NGEN, NHV1, '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE,
                0, 100, 0.018462, 0, 1, 1, 1, null, Double.NaN, Double.NaN, Double.NaN, EsgDetailedTwoWindingTransformer.RegulatingMode.NOT_REGULATING);
        twt1.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(1, 0, 22.8, 380, 0.769231));
        network.addDetailedTwoWindingTransformer(twt1);

        EsgDetailedTwoWindingTransformer twt2 = new EsgDetailedTwoWindingTransformer(new EsgBranchName(NHV2, NLOAD, '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE,
                0, 100, 0.021, 0, 1, 2, 2, NLOAD, 158, Double.NaN, Double.NaN, EsgDetailedTwoWindingTransformer.RegulatingMode.VOLTAGE);
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(1, 0, 446.4633, 150., 1.8));
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(2, 0, 379.4938, 150., 1.8));
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(3, 0, 329.9946, 150., 1.8));
        network.addDetailedTwoWindingTransformer(twt2);

        // Create loads
        network.addLoad(new EsgLoad(EsgConnectionStatus.CONNECTED, name("LOAD"), NLOAD, 0, 0, 600, 0, 0, 200.0));

        // Create generators
        network.addGenerator(new EsgGenerator(name("GEN"), NGEN, -9999.99, 607, 9999.99, -9999.99, 301, 9999.99,
                EsgRegulatingMode.REGULATING, 24.5, NGEN, 1, EsgConnectionStatus.CONNECTED));

        return network;
    }

    public static EsgNetwork createHvdc() {
        EsgNetwork network = new EsgNetwork();

        // Create areas
        network.addArea(new EsgArea(FA, EsgArea.Type.AC));
        network.addArea(new EsgArea(FR, EsgArea.Type.AC));
        network.addArea(new EsgArea(DC, EsgArea.Type.DC));

        // Create nodes
        network.addNode(new EsgNode(FA, name("FAKENOD1"), 380, 1, 0, false));
        network.addNode(new EsgNode(FA, name("FAKENOD2"), 380, 1, 0, false));
        network.addNode(new EsgNode(FR, B1, 400, 1, 0, true));
        network.addNode(new EsgNode(FR, VL2_0, 400, 1, 0, false));
        network.addNode(new EsgNode(FR, VL2_2, 400, 1, 0, false));

        // Create coupling device
        network.addCouplingDevice(new EsgCouplingDevice(new EsgBranchName(VL2_0, VL2_2, '1'), EsgCouplingDevice.ConnectionStatus.CLOSED));

        // Create loads
        network.addLoad(new EsgLoad(EsgConnectionStatus.CONNECTED, name("fict_C1"), B1, 0, 0, 3.4475, 0, 0, 0));
        network.addLoad(new EsgLoad(EsgConnectionStatus.CONNECTED, name("fict_C2"), VL2_2, 0, 0, 3.08, 0, 0, 0));

        // Create generators
        network.addGenerator(new EsgGenerator(name("G1"), B1, 50, 100, 150, -2147483, Double.NaN, 21474836,
                EsgRegulatingMode.REGULATING, 400, B1, 1, EsgConnectionStatus.CONNECTED));

        // Create DC nodes
        network.addDCNode(new EsgDCNode(DC, DC_C1, 800, 1));
        network.addDCNode(new EsgDCNode(DC, DC_C2, 800, 1));

        // Create DC link
        network.addDCLink(new EsgDCLink(DC_C1, DC_C2, '1', 1f, EsgDCLink.LinkStatus.ON));

        // Create converter stations
        network.addAcdcVscConverter(new EsgAcdcVscConverter(name("C1"), DC_C1, GROUND, B1,
                EsgAcdcVscConverter.ConverterState.ON, EsgAcdcVscConverter.DCControlMode.DC_VOLTAGE, EsgAcdcVscConverter.ACControlMode.AC_REACTIVE_POWER,
                0, 16, -276.92, 800, Double.NaN, 50, Double.NaN, 1, -300, 300, 0., 10., 0, 0, 0, Double.NaN, Double.NaN));
        network.addAcdcVscConverter(new EsgAcdcVscConverter(name("C2"), DC_C2, GROUND, VL2_2,
                EsgAcdcVscConverter.ConverterState.ON, EsgAcdcVscConverter.DCControlMode.AC_ACTIVE_POWER, EsgAcdcVscConverter.ACControlMode.AC_REACTIVE_POWER,
                0, 16, 276.92, 800, Double.NaN, -123, Double.NaN, 1, -300, 300, 0., 10., 0, 0, 0, Double.NaN, Double.NaN));

        return network;
    }

    private static Esg8charName name(String name) {
        return new Esg8charName(name);
    }
}
