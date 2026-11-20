# Third-party notices

The `hd` distribution (`cli/build/install/hd/lib`) bundles the following components unmodified.

| Component | Version | License | Source |
|---|---|---|---|
| Eclipse Memory Analyzer (MAT) core: `org.eclipse.mat.api`, `.parser`, `.hprof`, `.report` | 1.17.0.202606011933 | EPL-2.0 | https://github.com/eclipse-mat/mat, https://eclipse.dev/mat/ |
| Eclipse Platform: `org.eclipse.core.runtime`, `.contenttype`, `.jobs`, `org.eclipse.equinox.common`, `.registry`, `.preferences`, `.app`, `org.eclipse.osgi` | see `lib/` | EPL-2.0 | https://github.com/eclipse-platform, https://github.com/eclipse-equinox |
| OSGi `org.osgi.service.prefs`, `osgi.annotation` | see `lib/` | Apache-2.0 | https://github.com/osgi/osgi |
| Jackson (`jackson-core`, `-databind`, `-annotations`) | see `lib/` | Apache-2.0 | https://github.com/FasterXML/jackson |
| picocli | 4.7.7 | Apache-2.0 | https://github.com/remkop/picocli |

The Eclipse Public License 2.0 is available at https://www.eclipse.org/legal/epl-2.0/ and the Apache License 2.0 at
https://www.apache.org/licenses/LICENSE-2.0. EPL-2.0 components are used through their public and internal APIs
from separate source files (`engine-mat`); none of their source files are modified.
