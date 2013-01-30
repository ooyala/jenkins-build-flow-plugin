/** Test case for the BuildSpec class exposed in the DSL.
 *
 * copyright Corey O'Connor <corey@ooyala.com>
 */
package com.cloudbees.plugins.flow

import hudson.model.Job
import static hudson.model.Result.SUCCESS

class BuildSpecTest extends DSLTestCase {
    public void testDefaultBuildSpec() {
        Job job1 = createJob("job1")
        def flow = run("""
            spec = new BuildSpec("job1")
            spec.build
""")
        def build = assertSuccess(job1)
        assert SUCCESS == flow.result
    }
}
