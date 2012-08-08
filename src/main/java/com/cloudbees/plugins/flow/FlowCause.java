package com.cloudbees.plugins.flow;

import hudson.model.Cause;

/**
 * @author: <a hef="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class FlowCause extends Cause {

    private final transient FlowRun flowRun;

    public FlowCause(FlowRun flowRun) {
        this.flowRun = flowRun;
    }

    @Override
    public String getShortDescription() {
        if(null == flowRun) {
            return "Started by unknown build flow";
        } else {
            return "Started by build flow " + flowRun.toString();
        }
    }
}
