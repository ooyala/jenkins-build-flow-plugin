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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.Logger;

import net.sf.json.JSONException;
import net.sf.json.JSONObject;

/**
 * Defines the logic for the immunity job, which is basically a BuildFlow job with a predefined groovy
 * script.
 *
 * @author Noam Szpiro
 */
public class Immunity extends BuildFlow{

    public Immunity(ItemGroup parent, String name) {
        super(parent, name);
    }

    private static final Logger LOGGER = Logger.getLogger(Immunity.class.getName());

    @Extension
    public static final ImmunityDescriptor DESCRIPTOR = new ImmunityDescriptor();

    @Override
    public ImmunityDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    public String runShellCommand(String command) throws IOException, InterruptedException{
        Runtime run = Runtime.getRuntime();
        String output = "";
        Process pr = run.exec(command);
        int exitValue = pr.waitFor();
        if (exitValue != 0)
            LOGGER.severe("Invalid command, " + command + " exited with value " + exitValue);
        BufferedReader buf = new BufferedReader( new InputStreamReader( pr.getInputStream() ) ) ;
        String line;
        while ((line = buf.readLine()) != null)
            output += line + "\n";
        return output;
    }

    private String getImmunityGroovyScript() {
        try {
          runShellCommand("rm -rf temp");
          runShellCommand("/usr/bin/git clone ssh://git@git.corp.ooyala.com/jenkins-configs.git temp/jenkins-configs");
          return runShellCommand("cat temp/jenkins-configs/immunity.groovy");
        } catch (IOException e) {
          LOGGER.severe("failed to run shell command");
          e.printStackTrace();
        } catch (InterruptedException e) {
          LOGGER.severe("failed to run shell command due to interrupt");
          e.printStackTrace();
        }
        return "";
    }


    @Override
    public String getDsl() {
        String immunityHash = super.getDsl();
        if (immunityHash == null || immunityHash.length() == 0)
            return immunityHash;
        // Removing the quotes around the string. not sure why there are quotes here to begin with.
        immunityHash = immunityHash.substring(1, immunityHash.length() - 1);

        String immunityGroovy = getImmunityGroovyScript();

        LOGGER.info("immunity groovy without substitution: " + immunityGroovy);
        try {
            JSONObject json = JSONObject.fromObject(immunityHash);
            for (Object key: json.keySet()) {
                immunityGroovy = immunityGroovy.replaceAll("@" + key.toString().toUpperCase(),
                        "'" + json.get(key).toString() + "'");
            }
            LOGGER.info("immunity groovy with substitution: " + immunityGroovy);
        } catch (JSONException e) {
            LOGGER.severe("The hash received was an invalid one. " + immunityHash);
            return super.getDsl();
        }
        return immunityGroovy;
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
