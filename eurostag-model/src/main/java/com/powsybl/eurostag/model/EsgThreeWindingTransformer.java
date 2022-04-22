/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * @author Jean-Baptiste Heyberger <jean-baptiste.heyberger at rte-france.com>
 */
public class EsgThreeWindingTransformer {

    public enum RegulatingMode {
        NOT_REGULATING,
        VOLTAGE
    }

    public static class Tap {
        private final int iplo; // tap number
        private final double uno1; //  side 1 voltage [kV]
        private final double uno2; //  side 2 voltage [kV]
        private final double uno3; //  side 3 voltage [kV]
        private final double ucc12; // leakage impedance of winding 1-2 [%]
        private final double ucc13; // leakage impedance of winding 1-3 [%]
        private final double ucc23; // leakage impedance of winding 2-3 [%]

        private final double dephas1; // phase shift angle of winding 1 [deg]
        private final double dephas2; // phase shift angle of winding 2 [deg]
        private final double dephas3; // phase shift angle of winding 3 [deg]

        public Tap(int iplo, double dephas1, double dephas2, double dephas3, double uno1, double uno2, double uno3, double ucc12, double ucc13, double ucc23) {
            this.iplo = iplo;
            this.dephas1 = dephas1;
            this.dephas2 = dephas2;
            this.dephas3 = dephas3;
            this.uno1 = uno1;
            this.uno2 = uno2;
            this.uno3 = uno3;
            this.ucc12 = ucc12;
            this.ucc13 = ucc13;
            this.ucc23 = ucc23;
        }

        public double getDephas1() {
            return dephas1;
        }

        public double getDephas2() {
            return dephas2;
        }

        public double getDephas3() {
            return dephas3;
        }

        public int getIplo() {
            return iplo;
        }

        public double getUcc12() {
            return ucc12;
        }

        public double getUcc13() {
            return ucc13;
        }

        public double getUcc23() {
            return ucc23;
        }

        public double getUno1() {
            return uno1;
        }

        public double getUno2() {
            return uno2;
        }

        public double getUno3() {
            return uno3;
        }

    }

    public enum EsgT3WConnectionStatus {
        CLOSED_AT_ALL_SIDES,
        OPEN_AT_1_END_SIDE,
        OPEN_AT_2_END_SIDE,
        OPEN_AT_3_END_SIDE,
        OPEN_AT_12_END_SIDES,
        OPEN_AT_13_END_SIDES,
        OPEN_AT_23_END_SIDES,
        OPEN_AT_ALL_SIDES
    }

    public static class EsgT3WName {
        private final Esg8charName t3wName;
        private final Esg8charName node1Name;
        private final Esg8charName node2Name;
        private final Esg8charName node3Name;

        public EsgT3WName(Esg8charName t3wName, Esg8charName node1Name, Esg8charName node2Name, Esg8charName node3Name) {
            this.t3wName = Objects.requireNonNull(t3wName);
            this.node1Name = Objects.requireNonNull(node1Name);
            this.node2Name = Objects.requireNonNull(node2Name);
            this.node3Name = Objects.requireNonNull(node3Name);
        }

        public Esg8charName gett3wName() {
            return t3wName;
        }

        public Esg8charName getNode1Name() {
            return node1Name;
        }

        public Esg8charName getNode2Name() {
            return node2Name;
        }

        public Esg8charName getNode3Name() {
            return node3Name;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof EsgThreeWindingTransformer.EsgT3WName) {
                EsgThreeWindingTransformer.EsgT3WName other = (EsgThreeWindingTransformer.EsgT3WName) obj;
                return  t3wName.equals(other.t3wName) && node1Name.equals(other.node1Name) && node2Name.equals(other.node2Name) && node3Name.equals(other.node3Name);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(t3wName, node1Name, node2Name, node3Name);
        }

        @Override
        public String toString() {
            return "T3W_____";
        }

    }

    private final EsgT3WName name;
    private final EsgT3WConnectionStatus status;
    private final double rate1; // winding 1 rated apparent power [MVA]
    private final double rate2; // winding 2 rated apparent power [MVA]
    private final double rate3; // winding 3 rated apparent power [MVA]
    private final double pcu12; // Cu losses [% base RATE]
    private final double pcu13; // Cu losses [% base RATE]
    private final double pcu23; // Cu losses [% base RATE]
    private final double pfer; // Iron losses [% base RATE]
    private final double cmagn; // magnetizing current [%]
    private final double esat; // saturation exponent

    private final int ktpnom; // nominal tap number
    private final int ktap8; // initial tap position (tap number)
    private final Esg8charName zbusr; // regulated node name (if empty, no tap change)
    private double voltr; // voltage target [kV]
    private final EsgThreeWindingTransformer.RegulatingMode xregtr; // regulating mode

    private final List<EsgThreeWindingTransformer.Tap> taps = new ArrayList<>(1);

    public EsgThreeWindingTransformer(EsgT3WName name, EsgT3WConnectionStatus status, double cmagn,
                                            double rate1, double rate2, double rate3, double pcu12, double pcu13, double pcu23, double pfer, double esat, int ktpnom, int ktap8, Esg8charName zbusr,
                                            double voltr, EsgThreeWindingTransformer.RegulatingMode xregtr) {
        this.name = Objects.requireNonNull(name);
        this.status = Objects.requireNonNull(status);
        this.cmagn = cmagn;
        this.rate1 = rate1;
        this.rate2 = rate2;
        this.rate3 = rate3;
        this.pcu12 = pcu12;
        this.pcu13 = pcu13;
        this.pcu23 = pcu23;
        this.pfer = pfer;
        this.esat = esat;
        this.ktpnom = ktpnom;
        this.ktap8 = ktap8;
        this.zbusr = zbusr;
        this.voltr = voltr;
        this.xregtr = Objects.requireNonNull(xregtr);
    }

    public EsgT3WName getName() {
        return name;
    }

    public EsgT3WConnectionStatus getStatus() {
        return status;
    }

    public double getCmagn() {
        return cmagn;
    }

    public double getEsat() {
        return esat;
    }

    public int getKtap8() {
        return ktap8;
    }

    public int getKtpnom() {
        return ktpnom;
    }

    public double getPcu12() {
        return pcu12;
    }

    public double getPcu13() {
        return pcu13;
    }

    public double getPcu23() {
        return pcu23;
    }

    public double getPfer() {
        return pfer;
    }

    public double getRate1() {
        return rate1;
    }

    public double getRate2() {
        return rate2;
    }

    public double getRate3() {
        return rate3;
    }

    public List<EsgThreeWindingTransformer.Tap> getTaps() {
        return taps;
    }

    public double getVoltr() {
        return voltr;
    }

    public void setVoltr(double voltr) {
        this.voltr = voltr;
    }

    public EsgThreeWindingTransformer.RegulatingMode getXregtr() {
        return xregtr;
    }

    public Esg8charName getZbusr() {
        return zbusr;
    }

}
