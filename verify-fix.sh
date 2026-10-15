#!/usr/bin/env bash
#
# Applies the guards proposed in quarkusio/quarkus#56595 to the released resteasy-reactive-vertx artifact and
# installs the result as <version>-guarded in the local Maven repository, so that the reproducer can be run against
# the fix without building Quarkus from source:
#
#   ./verify-fix.sh 3.40.1
#   mvn clean test -Dresteasy-reactive-vertx.version=3.40.1-guarded
#
set -euo pipefail

VERSION="${1:-3.40.1}"
BASE="https://repo1.maven.org/maven2/io/quarkus/resteasy/reactive/resteasy-reactive-vertx/${VERSION}"
CLASS="org/jboss/resteasy/reactive/server/vertx/VertxResteasyReactiveRequestContext.java"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "==> downloading resteasy-reactive-vertx ${VERSION}"
curl -fsSL -o "${WORK}/sources.jar" "${BASE}/resteasy-reactive-vertx-${VERSION}-sources.jar"
curl -fsSL -o "${WORK}/orig.jar" "${BASE}/resteasy-reactive-vertx-${VERSION}.jar"
curl -fsSL -o "${WORK}/orig.pom" "${BASE}/resteasy-reactive-vertx-${VERSION}.pom"
mkdir -p "${WORK}/src" "${WORK}/classes" "${WORK}/jar"
unzip -qo "${WORK}/sources.jar" -d "${WORK}/src"

echo "==> adding the isEnded() guards"
python3 - "${WORK}/src/${CLASS}" <<'PY'
import sys, pathlib

path = pathlib.Path(sys.argv[1])
source = path.read_text()
edits = [
    # constructor
    ("""        };
        request.pause();
    }""",
     """        };
        if (!request.isEnded()) {
            request.pause();
        }
    }"""),
    # pauseRequestInput()
    ("""    public ServerHttpResponse pauseRequestInput() {
        request.pause();""",
     """    public ServerHttpResponse pauseRequestInput() {
        if (!request.isEnded()) {
            request.pause();
        }"""),
    # resumeRequestInput()
    ("""        request.resume();
        return this;
    }""",
     """        if (!request.isEnded()) {
            request.resume();
        }
        return this;
    }"""),
    # setReadListener(): an ended request with no buffered body has nothing left to deliver
    ("""            callback.done();
            return this;
        }
        request.pause();""",
     """            callback.done();
            return this;
        }
        if (request.isEnded()) {
            callback.done();
            return this;
        }
        request.pause();"""),
]
for old, new in edits:
    if old not in source:
        sys.exit("pattern not found, the source of this version differs:\n" + old)
    source = source.replace(old, new, 1)
path.write_text(source)
PY

echo "==> compiling"
mvn -q -B dependency:build-classpath "-Dmdep.outputFile=${WORK}/cp.txt" -DincludeScope=compile \
    "-Dquarkus.platform.version=${VERSION}" -Dresteasy-reactive-vertx.version="${VERSION}"
javac -nowarn -proc:none -cp "$(cat "${WORK}/cp.txt")" -d "${WORK}/classes" "${WORK}/src/${CLASS}"

echo "==> repackaging and installing ${VERSION}-guarded"
(cd "${WORK}/jar" && unzip -qo "${WORK}/orig.jar")
cp "${WORK}/classes/$(dirname "${CLASS}")"/*.class "${WORK}/jar/$(dirname "${CLASS}")/"
(cd "${WORK}/jar" && jar cf "${WORK}/guarded.jar" .)
python3 - "${WORK}/orig.pom" "${VERSION}" <<'PY'
import sys, pathlib

path = pathlib.Path(sys.argv[1])
source = path.read_text()
marker = "    <artifactId>resteasy-reactive-vertx</artifactId>\n"
if marker not in source:
    sys.exit("unexpected pom layout")
source = source.replace(marker, marker + "    <version>%s-guarded</version>\n" % sys.argv[2], 1)
path.write_text(source)
PY
mvn -q -B install:install-file -Dfile="${WORK}/guarded.jar" -DpomFile="${WORK}/orig.pom"

echo
echo "installed io.quarkus.resteasy.reactive:resteasy-reactive-vertx:${VERSION}-guarded"
echo "now run: mvn clean test -Dquarkus.platform.version=${VERSION} -Dresteasy-reactive-vertx.version=${VERSION}-guarded"
