/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

import java.util.Objects;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 */
public class EsgAcdcVscConverter {

    public enum ConverterState {
        ON,
        OFF
    }

    public enum DCControlMode {
        AC_ACTIVE_POWER,
        DC_VOLTAGE
    }

    public enum ACControlMode {
        AC_VOLTAGE,
        AC_REACTIVE_POWER,
        AC_POWER_FACTOR
    }

    private final Esg8charName znconv; // converter name
    private final Esg8charName dcNode1; // sending DC node name
    private final Esg8charName dcNode2; // receiving DC node name
    private final Esg8charName acNode; // AC node name
    private final ConverterState xstate; // converter state ' ' ON; 'S' OFF
    private final DCControlMode xregl; // DC control mode 'P' AC_ACTIVE_POWER; 'V' DC_VOLTAGE
    private final ACControlMode xoper; // AC control mode 'V' AC_VOLTAGE; 'Q' AC_REACTIVE_POWER; 'A' AC_POWER_FACTOR
    private final double rrdc; // resistance [Ohms]
    private final double rxdc; // reactance [Ohms]
    private final double pac; // AC active power setpoint [MW]. Only if DC control mode is 'P'
    private final double pvd; // DC voltage setpoint [MW]. Only if DC control mode is 'V'
    private final double pva; // AC voltage setpoint [kV]. Only if AC control mode is 'V'
    private final double pre; // AC reactive power setpoint [Mvar]. Only if AC control mode is 'Q'
    private final double pco; // AC power factor setpoint. Only if AC control mode is 'A'
    private final double qvscsh; // Reactive sharing cofficient [%]. Only if AC control mode is 'V'
    private final double pvscmin; // Minimum AC active power [MW]
    private final double pvscmax; // Maximum AC active power [MW]
    private final double qvscmin; // Minimum reactive power injected on AC node [kV]
    private final double qvscmax; // Maximum reactive power injected on AC node [kV]
    private final double vsb0; // Losses coefficient Beta0 [MW]
    private final double vsb1; // Losses coefficient Beta1 [kW]
    private final double vsb2; // Losses coefficient Beta2 [Ohms]
    private final double mvm; // Initial AC modulated voltage magnitude [p.u.]
    private final double mva; // Initial AC modulated voltage angle [deg]

    public EsgAcdcVscConverter(Esg8charName znconv,
                               Esg8charName dcNode1,
                               Esg8charName dcNode2,
                               Esg8charName acNode,
                               ConverterState xstate,
                               DCControlMode xregl,
                               ACControlMode xoper,
                               double rrdc,
                               double rxdc,
                               double pac,
                               double pvd,
                               double pva,
                               double pre,
                               double pco,
                               double qvscsh,
                               double pvscmin,
                               double pvscmax,
                               double qvscmin,
                               double qvscmax,
                               double vsb0,
                               double vsb1,
                               double vsb2,
                               double mvm,
                               double mva) {
        this.znconv = Objects.requireNonNull(znconv);
        this.dcNode1 = Objects.requireNonNull(dcNode1);
        this.dcNode2 = Objects.requireNonNull(dcNode2);
        this.acNode = Objects.requireNonNull(acNode);
        this.xstate = Objects.requireNonNull(xstate);
        this.xregl = Objects.requireNonNull(xregl);
        this.xoper = Objects.requireNonNull(xoper);
        this.rrdc = rrdc;
        this.rxdc = rxdc;
        this.pac = pac;
        this.pvd = pvd;
        this.pva = pva;
        this.pre = pre;
        this.pco = pco;
        this.qvscsh = qvscsh;
        this.pvscmin = pvscmin;
        this.pvscmax = pvscmax;
        this.qvscmin = qvscmin;
        this.qvscmax = qvscmax;
        this.vsb0 = vsb0;
        this.vsb1 = vsb1;
        this.vsb2 = vsb2;
        this.mvm = mvm;
        this.mva = mva;
    }

    public Esg8charName getZnconv() {
        return znconv;
    }

    public Esg8charName getDcNode1() {
        return dcNode1;
    }

    public Esg8charName getDcNode2() {
        return dcNode2;
    }

    public Esg8charName getAcNode() {
        return acNode;
    }

    public ConverterState getXstate() {
        return xstate;
    }

    public DCControlMode getXregl() {
        return xregl;
    }

    public ACControlMode getXoper() {
        return xoper;
    }

    public double getRrdc() {
        return rrdc;
    }

    public double getRxdc() {
        return rxdc;
    }

    public double getPac() {
        return pac;
    }

    public double getPvd() {
        return pvd;
    }

    public double getPva() {
        return pva;
    }

    public double getPre() {
        return pre;
    }

    public double getPco() {
        return pco;
    }

    public double getQvscsh() {
        return qvscsh;
    }

    public double getPvscmin() {
        return pvscmin;
    }

    public double getPvscmax() {
        return pvscmax;
    }

    public double getQvscmin() {
        return qvscmin;
    }

    public double getQvscmax() {
        return qvscmax;
    }

    public double getVsb0() {
        return vsb0;
    }

    public double getVsb1() {
        return vsb1;
    }

    public double getVsb2() {
        return vsb2;
    }

    public double getMvm() {
        return mvm;
    }

    public double getMva() {
        return mva;
    }
}
