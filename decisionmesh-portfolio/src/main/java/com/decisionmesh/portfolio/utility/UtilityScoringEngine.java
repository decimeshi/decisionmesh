package com.decisionmesh.portfolio.utility;

public class UtilityScoringEngine {

    public double computeUtility(double satisfaction,
                                 double cost,
                                 double risk,
                                 double priorityWeight) {

        return (satisfaction * priorityWeight)
             - (cost * 0.3)
             - (risk * 0.5);
    }
}