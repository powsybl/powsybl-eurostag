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
public final class EurostagFactory {

    private static final String FR = "FR";
    private static final String FA = "FA";

    private EurostagFactory() {
    }

    public static EsgNetwork create() {
        EsgNetwork network = new EsgNetwork();

        // Create areas
        network.addArea(new EsgArea(new Esg2charName(FA), EsgArea.Type.AC));
        network.addArea(new EsgArea(new Esg2charName(FR), EsgArea.Type.AC));

        // Create nodes
        network.addNode(new EsgNode(new Esg2charName(FA), name("FAKENOD1"), 380.0, 1.0, 0.0, false));
        network.addNode(new EsgNode(new Esg2charName(FA), name("FAKENOD2"), 380.0, 1.0, 0.0, false));
        network.addNode(new EsgNode(new Esg2charName(FR), name("NGEN"), 24.0, 1.0, 0.0, true));
        network.addNode(new EsgNode(new Esg2charName(FR), name("NHV1"), 380.0, 1.0, 0.0, false));
        network.addNode(new EsgNode(new Esg2charName(FR), name("NHV2"), 380.0, 1.0, 0.0, false));
        network.addNode(new EsgNode(new Esg2charName(FR), name("NLOAD"), 150.0, 1.0, 0.0, false));

        // Create lines
        network.addLine(new EsgLine(new EsgBranchName(name("NHV1"), name("NHV2"), '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE, 0.002078, 0.022853, 0.0, 0.278692, 100));
        network.addLine(new EsgLine(new EsgBranchName(name("NHV1"), name("NHV2"), '2'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE, 0.002078, 0.022853, 0.0, 0.278692, 100));

        // Create transformers
        EsgDetailedTwoWindingTransformer twt1 = new EsgDetailedTwoWindingTransformer(new EsgBranchName(name("NGEN"), name("NHV1"), '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE,
                0.0, 100.0, 0.018462, 0.0, 1.0, 1, 1, null, 0.0, 0.0, 0.0, EsgDetailedTwoWindingTransformer.RegulatingMode.NOT_REGULATING);
        twt1.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(1, 0.0, 22.8, 380.0, 0.769231));
        network.addDetailedTwoWindingTransformer(twt1);

        EsgDetailedTwoWindingTransformer twt2 = new EsgDetailedTwoWindingTransformer(new EsgBranchName(name("NHV2"), name("NLOAD"), '1'), EsgBranchConnectionStatus.CLOSED_AT_BOTH_SIDE,
                0.0, 100.0, 0.021, 0.0, 1.0, 2, 2, name("NLOAD"), 158.0, 0.0, 0.0, EsgDetailedTwoWindingTransformer.RegulatingMode.VOLTAGE);
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(1, 0.0, 446.4633, 150., 1.8));
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(2, 0.0, 379.4938, 150., 1.8));
        twt2.getTaps().add(new EsgDetailedTwoWindingTransformer.Tap(3, 0.0, 329.9946, 150., 1.8));
        network.addDetailedTwoWindingTransformer(twt2);

        // Create loads
        network.addLoad(new EsgLoad(EsgConnectionStatus.CONNECTED, name("LOAD"), name("NLOAD"), 0.0, 0.0, 600.0, 0.0, 0.0, 200.0));

        // Create generators
        network.addGenerator(new EsgGenerator(name("GEN"), name("NGEN"), -9999.99, 607, 9999.99, -9999.99, 301.0, 9999.99, EsgRegulatingMode.REGULATING, 24.5, name("NGEN"), 1.0, EsgConnectionStatus.CONNECTED));

        return network;
    }

    private static Esg8charName name(String name) {
        return new Esg8charName(name);
    }
}
