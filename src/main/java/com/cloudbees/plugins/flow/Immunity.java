/*
 * Copyright (C) 2011 CloudBees Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins.flow;

import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.TopLevelItem;
import hudson.model.AbstractProject;

import hudson.tasks.Fingerprinter;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

/**
 * Defines the orchestration logic for a build flow as a succession o jobs to be executed and chained together
 *
 * @author <a href="mailto:nicolas.deloof@cloudbees.com">Nicolas De loof</a>
 */
public class Immunity extends BuildFlow{

    public Immunity(ItemGroup parent, String name) {
        super(parent, name);
    }

    private static final Logger LOGGER = Logger.getLogger(Immunity.class.getName());

    private final String immunityGroovy = "// using the defaults for the other commands\n" +
            "deploy_run = buildOn(\"c3-jenkins6\", \"generic_deploy\", MAIN_REPO: ${MAIN_REPO}, " +
            "DEPLOY_COMMAND: @DEPLOY_COMMAND, REGION: @REGION, DEPENDENT_REPO: @DEPENDENT_REPO\n" +
            "out.println(\"deploy run result: ${deploy_run.result}\")\n" +
            "tests_run = buildOn(\"c3-jenkins6\", \"generic_tests\", MAIN_REPO: @MAIN_REPO, " +
            "TESTS_COMMAND: @TEST_COMMAND, REGION: @REGION, DEPENDENT_REPO: @DEPENDENT_REPO\n" +
            "out.println(\"tests run result: ${tests_run.result}\")";

    @Extension
    public static final ImmunityDescriptor DESCRIPTOR = new ImmunityDescriptor();

    @Override
    public ImmunityDescriptor getDescriptor() {
        return DESCRIPTOR;
    }


    @Override
    public String getDsl() {
        String dsl = super.getDsl();
        if (dsl == null)
            return null;
        // Removing the quotes around the string. not sure why there are quotes here to begin with.
        dsl = dsl.substring(1,dsl.length() - 1);

        JSONObject json = JSONObject.fromObject(dsl);
        for (Object key: json.keySet()) {
            dsl = immunityGroovy.replaceAll("@" + key.toString().toUpperCase(), json.get(key).toString());
        }
        return dsl;
    }


    public static class ImmunityDescriptor extends BuildFlowDescriptor {
        @Override
        public String getDisplayName() {
            return Messages.Immunity_Messages();
        }

        @Override
        public TopLevelItem newInstance(ItemGroup parent, String name) {
            return new Immunity(parent, name);
        }
    }
}
