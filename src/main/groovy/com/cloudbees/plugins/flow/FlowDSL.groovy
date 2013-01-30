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

package com.cloudbees.plugins.flow

import java.util.List;
import java.util.logging.Logger
import jenkins.model.Jenkins
import hudson.model.*
import static hudson.model.Result.SUCCESS
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

import java.util.concurrent.TimeUnit
import hudson.slaves.NodeProperty
import hudson.slaves.EnvironmentVariablesNodeProperty
import java.util.concurrent.CopyOnWriteArrayList
import hudson.console.HyperlinkNote
import java.util.concurrent.Future
import java.util.concurrent.Callable

import static hudson.model.Result.FAILURE
import java.util.concurrent.ExecutionException
import hudson.model.queue.SubTask
import com.thoughtworks.xstream.converters.collections.CollectionConverter

public class FlowDSL {

    private ExpandoMetaClass createEMC(Class scriptClass, Closure cl) {
        ExpandoMetaClass emc = new ExpandoMetaClass(scriptClass, false)
        cl(emc)
        emc.initialize()
        return emc
    }

    def void executeFlowScript(FlowRun flowRun, String dsl, BuildListener listener) {
        // TODO : add restrictions for System.exit, etc ...
        FlowDelegate flow = new FlowDelegate(flowRun, listener)

        // Retrieve the upstream build if the flow was triggered by another job
        AbstractBuild upstream = null;
        flowRun.causes.each{ cause -> 
            if (cause instanceof Cause.UpstreamCause) {
                Job job = Jenkins.instance.getItemByFullName(cause.upstreamProject)
                upstream = job?.getBuildByNumber(cause.upstreamBuild)
                // TODO handle matrix jobs ?
            }
        }

        def envMap = [:]
        def getEnvVars = { NodeProperty nodeProperty ->
            if (nodeProperty instanceof EnvironmentVariablesNodeProperty) {
                envMap.putAll( nodeProperty.envVars );
            }
        }
        Jenkins.instance.globalNodeProperties.each(getEnvVars)
        flowRun.builtOn.nodeProperties.each(getEnvVars)

        def binding = new Binding([
                build: flowRun,
                env: envMap,
                upstream: upstream,
                params: flowRun.getBuildVariables(),
                SUCCESS: SUCCESS,
                UNSTABLE: Result.UNSTABLE,
                FAILURE: Result.FAILURE,
                ABORTED: Result.ABORTED,
                NOT_BUILT: Result.NOT_BUILT
        ])

        try {
            Script dslScript = new GroovyShell(this.class.classLoader, binding).parse("flow { " + dsl + "}")
            dslScript.metaClass = createEMC(dslScript.class, {
                ExpandoMetaClass emc ->
                    emc.flow = {
                        Closure cl ->
                            cl.delegate = flow
                            cl.resolveStrategy = Closure.DELEGATE_FIRST
                            cl()
                    }
                    emc.println = {
                        String s -> flow.println s
                    }
            })

            dslScript.run()
        } catch(JobExecutionFailureException e) {
            listener.logger.println(e);
            println(e);
            flowRun.state.result = FAILURE;
        } catch(Exception e) {
            listener.logger.println(e);
            println(e);
            throw e;
        }
    }

    // TODO define a parseFlowScript to validate flow DSL and maintain jobs dependencygraph
}

public class NodeConstraintParam extends ParameterValue {
    public Label value;

    public NodeConstraintParam(String labelExpression) {
        super("UseNodeLabel")
        value = Label.parseExpression(labelExpression)
    }

    public NodeConstraintParam(Label label) {
        super("UseNodeLabel")
        this.value = label
    }

    @Override
    public Label getAssignedLabel(SubTask task) {
        return this.value
    }

    @Override
    public String toString() {
        return "Node Constraint: " + value.toString()
    }
}

public class BuildSpec {
    public BuildSpec(String jobName) {
    }
}

public class FlowDelegate {

    private static final Logger LOGGER = Logger.getLogger(FlowDelegate.class.getName());
    def List<Cause> causes
    def FlowRun flowRun
    BuildListener listener
    int indent = 0

    public FlowDelegate(FlowRun flowRun, BuildListener listener) {
        this.flowRun = flowRun
        this.listener = listener
        causes = flowRun.causes
    }

    def getOut() {
        return listener.logger
    }

    // TODO Assuring proper indent should be done in the listener?
    def synchronized println_with_indent(Closure f) {
        for (int i = 0; i < indent; ++i) {
            out.print("    ")
        }
        f()
        out.println()
    }

    def println(String s) {
        println_with_indent { out.println(s) }
    }

    def fail() {
        // Stop the flow execution
        throw new JobExecutionFailureException()
    }

    def build(String jobName) {
        build([:], jobName)
    }

    def buildOn(String labelExpr, String jobName) {
        buildOn([:], labelExpr, jobName)
    }

    /* Additional parameters overload defaults with the same name.
     */
    def build(Map in_args, String jobName) {
        if (flowRun.state.result.isWorseThan(SUCCESS)) {
            println("Skipping ${jobName}")
            fail()
        }
        // ask for job with name ${name}
        JobInvocation job = new JobInvocation(flowRun, jobName)

        def p = job.getProject()
        println("Trigger job " + HyperlinkNote.encodeTo('/'+ p.getUrl(), p.getFullDisplayName()))

        // Start with the default arguments then add all the arguments provided to build.
        // This means that parameters provided to build will override default parameters.
        def args = defaultParams(p)
        args.putAll(in_args)

        Run r = flowRun.run(job, actionsForArgs(args))

        if (null == r) {
            println("Failed to start ${jobName}.")
            fail();
        }

        println(HyperlinkNote.encodeTo('/'+ r.getUrl(), r.getFullDisplayName())+" completed")
        return job;
    }

    def buildOn(Map in_args, String labelExpr, String jobName) {
        in_args.put("", new NodeConstraintParam(labelExpr))
        build(in_args, jobName)
    }

    def defaultParams(AbstractProject project) {
        Map out = new HashMap()

        // There is a private method of project called getDefaultParameters that could greatly simplify all this.
        ParametersDefinitionProperty projectParams = project.getProperty(ParametersDefinitionProperty.class)
        if(null == projectParams)
            return out

        for(ParameterDefinition parameterDefinition : projectParams.getParameterDefinitions()) {
            ParameterValue defaultValue = parameterDefinition.getDefaultParameterValue()
            if(null == defaultValue)
                continue

            if (defaultValue instanceof BooleanParameterValue) {
                out.put(parameterDefinition.getName(), ((BooleanParameterValue)defaultValue).value)
            }
            if (defaultValue instanceof  StringParameterValue) {
                out.put(parameterDefinition.getName(), ((StringParameterValue)defaultValue).value)
            }
        }

        return out
    }

    def actionsForArgs(Map args) {
        List<Action> actions = new ArrayList<Action>();

        List<ParameterValue> params = [];

        for (Map.Entry param: args) {
            String paramName = param.key
            Object paramValue = param.value
            if (paramValue instanceof Closure) {
                paramValue = getClosureValue(paramValue)
            }
            if (paramValue instanceof Boolean) {
                params.add(new BooleanParameterValue(paramName, (Boolean) paramValue))
            }
            if (paramValue instanceof NodeConstraintParam) {
                params.add((NodeConstraintParam) paramValue)
            }
            else {
                params.add(new StringParameterValue(paramName, paramValue.toString()))
            }
            //TODO For now we only support String and boolean parameters
        }
        actions.add(new ParametersAction(params));
        return actions
    }

    def getClosureValue(closure) {
        return closure()
    }

    def guard(guardedClosure) {
        def deleg = this;
        [ rescue : { rescueClosure ->
            rescueClosure.delegate = deleg
            rescueClosure.resolveStrategy = Closure.DELEGATE_FIRST

            try {
                println("guard {")
                ++indent
                guardedClosure()
            } finally {
                --indent
                // Force result to SUCCESS so that rescue closure will execute
                Result r = flowRun.state.result
                flowRun.state.result = SUCCESS
                println("} rescue {")
                ++indent
                try {
                    rescueClosure()
                } finally {
                    --indent
                    println("}")
                }
                // restore result, as the worst from guarded and rescue closures
                flowRun.state.result = r.combine(flowRun.state.result)
            }
        } ]
    }

    def retry(int attempts, retryClosure) {
        Result origin = flowRun.state.result
        int i
        while( attempts-- > 0) {
            // Restore the pre-retry result state to ignore failures
            flowRun.state.result = origin
            println("retry (attempt $i++} {")
            ++indent

            retryClosure()

            --indent

            if (flowRun.state.result.isBetterOrEqualTo(SUCCESS)) {
                println("}")
                return;
            }

            println("} // failed")
        }
    }

    def parallel(Collection<? extends Closure> closures) {
        parallel(closures as Closure[])
    }

    /* Executes the provided closures in parallel.
     * parallel accepts a variable number of arguments. Each argument can be a closure or a collection of closures. EG:
      * parallel( { build("foo") }, { build("bar") } )
      * parallel( [ { build("foo" }, { build("bar") } ] )
      * parallel( { build("foo" }, [ build("bar"), build("baz") ])
     */
    def parallel(Object... in_closures) {
        def closures = in_closures.flatten()
        ExecutorService pool = Executors.newCachedThreadPool()
        Set<Run> upstream = flowRun.state.lastCompleted
        Set<Run> lastCompleted = Collections.synchronizedSet(new HashSet<Run>())
        def results = new CopyOnWriteArrayList<FlowState>()
        def tasks = new ArrayList<Future<FlowState>>()

        println("parallel {")
        ++indent

        def current_state = flowRun.state
        try {
            closures.each {closure ->
                Closure<FlowState> track_closure = {
                    flowRun.state = new FlowState(SUCCESS, upstream)
                    closure()
                    lastCompleted.addAll(flowRun.state.lastCompleted)
                    return flowRun.state
                }

                tasks.add(pool.submit(track_closure as Callable))
            }

            tasks.each {task ->
                try {
                    def final_state = task.get()
                    Result result = final_state.result
                    results.add(final_state)
                    current_state.result = current_state.result.combine(result)
                } catch(ExecutionException e)
                {
                    // TODO perhaps rethrow?
                    println(e.toString())
                    current_state.result = FAILURE
                }
            }

            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.DAYS)
            current_state.setLastCompleted(lastCompleted)
        } finally {
            flowRun.state = current_state
            --indent
            println("}")
        }
        return results
    }
    

    def propertyMissing(String name) {
        throw new MissingPropertyException("Property ${name} doesn't exist.");
    }

    def methodMissing(String name, Object args) {
        throw new MissingMethodException(name, this.class, args);
    }
}
