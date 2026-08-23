{
  description = "hearthd-kiosk";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        jdk = pkgs.jdk17;
        # AGP 9.x requires Gradle >= 9.5; nixpkgs' default `gradle` is still 8.x.
        gradle = pkgs.gradle_9;
        version = "0.1.0";

        # openWakeWord distributes its models as GitHub release assets rather
        # than on a package registry, so they can't ride in deps.json. Fetch
        # each by content hash instead — reproducible, and no model binaries in
        # git. Regenerate a hash after bumping a URL with:  nix-prefetch-url <url>
        owwBase = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1";
        owwModel = name: sha256: pkgs.fetchurl { url = "${owwBase}/${name}"; inherit sha256; };
        # Staged under stable asset names (the classifiers drop their _v0.1
        # suffix) so WakeWordModel can reference them without version churn.
        wakeWordModels = pkgs.runCommand "openwakeword-models" { } ''
          mkdir -p "$out"
          # Shared feature models, used by every wake word.
          cp ${owwModel "melspectrogram.onnx" "0vqpxdjzlyglv775r2gj6v2bllzzc0rv3768l9lm71vvic7hwaxs"} "$out/melspectrogram.onnx"
          cp ${owwModel "embedding_model.onnx" "07sw0z9ppzvc0lfz6nbb66km0cjl01gbqjg19qfms28x1hln9lbh"} "$out/embedding_model.onnx"
          # Pre-trained wake-word classifiers.
          cp ${owwModel "hey_jarvis_v0.1.onnx" "1jyjw0p72wsa8dcgphqvhrqfw8w15r37wbj7d8pi6nq7c3z3r8cl"} "$out/hey_jarvis.onnx"
          cp ${owwModel "alexa_v0.1.onnx" "0gmfzcyjhfcagwdn0ai72xfmgc8xclrdln9wks6hwrqj3nh6dxbg"} "$out/alexa.onnx"
          cp ${owwModel "hey_mycroft_v0.1.onnx" "060z3cq2sg4992nxwvcdk8pszm7z9pf4cfqvqf4xwf0kzbl138y2"} "$out/hey_mycroft.onnx"
        '';

        # Roboto Flex (variable, OFL) is the app's UI font, staged into assets by
        # the build like the wake-word models — no font binary in git. Sourced
        # from nixpkgs' google-fonts (pinned by the nixpkgs input, so nixpkgs owns
        # updates); the `fonts` filter installs just this one family.
        robotoFlex = pkgs.google-fonts.override { fonts = [ "RobotoFlex" ]; };

        # snapcast's snapclient, cross-built for aarch64-android as a
        # self-contained libsnapclient.so. Staged into the app's jniLibs like the
        # assets above (kept out of git), and exec'd at runtime as a subprocess.
        # Source rides the nixpkgs pin, so version bumps come with nixpkgs.
        snapclientAndroid = import ./snapclient-android.nix { inherit pkgs; };

        # Android SDK components needed to build the app. Keep these versions in
        # sync with compileSdk / build-tools in app/build.gradle.kts.
        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "37" ];
          buildToolsVersions = [ "36.0.0" ];
          includeEmulator = false;
          includeSystemImages = false;
          includeNDK = false;
        };
        androidSdk = androidComposition.androidsdk;
        sdkRoot = "${androidSdk}/libexec/android-sdk";

        # A Gradle build of this project running a single task. The app package
        # and the lint check are two derivations built from this so that neither
        # depends on the other: a lint failure never blocks the APK, and they
        # build/cache independently. Both replay the same deps.json.
        mkGradle =
          {
            pname,
            gradleBuildTask,
            installPhase,
            # Stamped into the APK build only (see gradleFlags). null → the build
            # falls back to the dev placeholders in app/build.gradle.kts.
            versionCode ? null,
            versionName ? null,
          }:
          pkgs.stdenv.mkDerivation (finalAttrs: {
            inherit pname version installPhase gradleBuildTask;

            src = ./.;

            nativeBuildInputs = [
              gradle
              jdk
            ];

            # Reproducible Gradle dependency resolution. Regenerate deps.json with:
            #   nix run .#update-deps
            mitmCache = gradle.fetchDeps {
              pkg = finalAttrs.finalPackage;
              data = ./deps.json;
            };

            # Stage the content-addressed openWakeWord models into the app's
            # assets before Gradle packages them. Kept out of the source tree so
            # no model binaries live in git (see wakeWordModels above); the
            # matching .gitignore entry stops a local build committing them.
            postPatch = ''
              mkdir -p app/src/main/assets/wakeword
              cp ${wakeWordModels}/* app/src/main/assets/wakeword/

              # Stage Roboto Flex under a stable, code-friendly name (the upstream
              # file carries its axis list in brackets). Loaded at runtime from
              # this path; see ui/theme/Typography.kt.
              mkdir -p app/src/main/assets/fonts
              cp ${robotoFlex}/share/fonts/truetype/RobotoFlex*.ttf \
                app/src/main/assets/fonts/roboto_flex.ttf

              # Stage the cross-built snapclient into jniLibs; AGP packages it
              # into the APK, and the app extracts + execs it (see SnapcastController).
              mkdir -p app/src/main/jniLibs/arm64-v8a
              cp ${snapclientAndroid}/jniLibs/arm64-v8a/libsnapclient.so \
                app/src/main/jniLibs/arm64-v8a/libsnapclient.so
            '';

            # Point the Android Gradle Plugin at the Nix-provided SDK.
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;

            # Lint's Google Play SDK Index check fetches a rolling snapshot.gz
            # from dl.google.com during lint-model generation. That blob has no
            # stable content hash, so the recording proxy pins a moving target
            # into deps.json and the build stops reproducing. This env var (read
            # by lint's GooglePlaySdkIndex) overrides the snapshot base URL; a
            # non-network file:// URL that does not exist makes the fetch fail
            # cleanly, so lint falls back to the offline snapshot bundled inside
            # the (already-pinned) AGP jar. Nothing mutable enters the lockfile.
            SDK_INDEX_TEST_BASE_URL = "file:///var/empty/hearthd-no-play-sdk-index/";

            # Same failure mode, different fetch: lint's GradleDetector calls the
            # Google Maven index (maven.google.com master-index.xml + a per-group
            # group-index.xml) unconditionally while checking whether a newer
            # library version exists. Those index files are rolling — Google
            # rewrites them whenever any artifact in the group ships — so the
            # recording proxy pins a moving target and the build stops reproducing
            # on a cold cache. This env var (read by GoogleMavenRepository as
            # GMAVEN_BASE_URL) points the index base at a non-network file:// path
            # that does not exist, so the fetch fails cleanly and lint falls back
            # to the offline index bundled in its jar. The app doesn't need it and
            # Renovate owns version bumps, so nothing mutable enters the lockfile.
            GMAVEN_TEST_BASE_URL = "file:///var/empty/hearthd-no-gmaven-index/";

            # AGP needs a writable "android home" for its prefs/analytics; the
            # sandbox HOME is read-only, so redirect it into the build tree. This
            # runs for both the dependency-fetch update and the real build.
            preConfigure = ''
              export ANDROID_USER_HOME="$NIX_BUILD_TOP/.android"
              mkdir -p "$ANDROID_USER_HOME"
            '';

            gradleFlags = [
              "-Dorg.gradle.java.home=${jdk}"
              # AGP downloads a prebuilt aapt2 from Maven that can't run on NixOS
              # (wrong ELF interpreter). Use the autopatched aapt2 from the
              # Nix-provided build-tools instead.
              "-Pandroid.aapt2FromMavenOverride=${sdkRoot}/build-tools/36.0.0/aapt2"
            ]
            # Stamp the version from the flake's own git input, so no source
            # mutation or --impure is needed. Only the APK build gets it; the
            # lint derivation stays version-independent.
            ++ pkgs.lib.optionals (versionCode != null) [
              "-Pkiosk.versionCode=${toString versionCode}"
              "-Pkiosk.versionName=${versionName}"
            ];

            # nixpkgs' default dependency-fetch task (nixDownloadDeps) resolves
            # every resolvable configuration, which fails on Android projects with
            # variant-ambiguity errors. Instead, record exactly what the real
            # tasks download by running them under the recording proxy. Both the
            # app and lint tasks are resolved so the shared deps.json serves both.
            gradleUpdateTask = "assembleDebug lintDebug";

            # No unit tests yet.
            doCheck = false;
          });

        hearthd-kiosk = mkGradle {
          pname = "hearthd-kiosk";
          gradleBuildTask = "assembleDebug";
          # versionCode is the tip's committer time (seconds since 2020), which
          # like revCount is intrinsic to the revision — no --impure, no external
          # counter. Time rather than height so it moves on amend/squash, not just
          # on new commits, and so both channels share one monotonic axis (mixing
          # scales would strand a device on whichever channel numbered higher).
          # The 2020 epoch keeps it ~2e8 today, an order of magnitude under the
          # 2^31 / Play 2.1e9 ceilings (raw epoch seconds would hit them ~2038).
          # A dirty local tree lacks `rev` → placeholders.
          versionCode = if self ? rev then self.lastModified - 1577836800 else 1;
          versionName = "0.1.0+${self.shortRev or "dirty"}";
          installPhase = ''
            runHook preInstall
            mkdir -p "$out"
            cp app/build/outputs/apk/debug/app-debug.apk \
              "$out/hearthd-kiosk-${version}-debug.apk"
            runHook postInstall
          '';
        };

        hearthd-kiosk-lint = mkGradle {
          pname = "hearthd-kiosk-lint";
          gradleBuildTask = "lintDebug";
          installPhase = ''
            runHook preInstall
            mkdir -p "$out"
            # lintDebug fails the build on lint errors, so reaching install means
            # lint passed; keep the report as the check's output.
            cp app/build/reports/lint-results-debug.* "$out/" 2>/dev/null || true
            runHook postInstall
          '';
        };
      in
      {
        packages = {
          default = hearthd-kiosk;
          hearthd-kiosk = hearthd-kiosk;
        };

        # `nix flake check` builds the APK and runs Android Lint. The build check
        # is the same derivation as the package, so a later `nix build
        # .#hearthd-kiosk` just fetches the already-built store path.
        checks = {
          build = hearthd-kiosk;
          lint = hearthd-kiosk-lint;
        };

        # `nix run .#update-deps` regenerates deps.json after changing dependencies.
        apps.update-deps = {
          type = "app";
          program = "${hearthd-kiosk.mitmCache.updateScript}";
        };

        devShells.default = pkgs.mkShell {
          packages = [
            gradle
            jdk
            androidSdk
            pkgs.android-tools # adb, for sideloading
          ];
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME = "${jdk}";
          shellHook = ''
            echo "hearthd-kiosk dev shell — gradle $(gradle --version | awk '/Gradle/ {print $2; exit}'), JDK 17, Android SDK ready."
            echo "Build the APK with:  nix build .#hearthd-kiosk"
          '';
        };

        # The publish workflow's toolbox, pinned to this flake's nixpkgs like
        # everything else. CI enters it once (via the nix-develop action) so the
        # sign/version/publish scripts find their tools on PATH: apksigner,
        # zipalign and aapt2 from the SDK build-tools (need ANDROID_HOME + a
        # JDK), and awscli2 for the R2 upload. Deliberately leaner than the
        # default shell — no gradle or adb, which CI never runs.
        devShells.ci = pkgs.mkShell {
          packages = [
            jdk
            androidSdk
            pkgs.awscli2
          ];
          ANDROID_HOME = sdkRoot;
          ANDROID_SDK_ROOT = sdkRoot;
          JAVA_HOME = "${jdk}";
        };
      }
    );
}
