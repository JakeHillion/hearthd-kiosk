# Cross-compiles snapcast's snapclient for aarch64-android as a self-contained,
# statically-linked libsnapclient.so. The app ships this in jniLibs and execs it
# as a subprocess (the naming trick Android uses to run a binary from an APK).
#
# Source comes from nixpkgs' `snapcast` package, so version bumps ride the
# nixpkgs pin like every other dependency here. The Android build isn't a
# nixpkgs target, so we drive the NDK's CMake toolchain ourselves and feed it
# statically-built codec libraries from the aarch64-android cross set. Only oboe
# (Google's Android audio library) is missing from nixpkgs and built from source.
#
# SSL/TLS is off: OpenSSL's kernel-TLS code doesn't cross-compile against bionic,
# and it's optional (BUILD_WITH_SSL). That means plain-TCP snapservers only — no
# wss:// or server auth.
{ pkgs }:

let
  lib = pkgs.lib;
  cross = pkgs.pkgsCross.aarch64-android-prebuilt;

  # NDK + its CMake toolchain file (sets CMAKE_SYSTEM_NAME=Android, which is what
  # snapcast's root CMakeLists keys off to take its Android branch). This NDK is
  # r29 while the codec libs below come off the cross set's r27 toolchain; that's
  # fine, they're pure C and ABI-stable across NDK versions.
  ndkBundle = pkgs.androidenv.androidPkgs.ndk-bundle;
  ndkVer = "29.0.14206865";
  ndkTool = "${ndkBundle}/libexec/android-sdk/ndk/${ndkVer}/toolchains/llvm/prebuilt/linux-x86_64";
  toolchain = "${ndkBundle}/libexec/android-sdk/ndk/${ndkVer}/build/cmake/android.toolchain.cmake";

  abi = "arm64-v8a";
  platform = "28"; # matches app minSdk (see app/build.gradle.kts)

  # c++_static so the resulting binary carries libc++ and needs no extra .so at
  # runtime — its only NEEDED libs end up being bionic system ones.
  androidCmakeFlags = [
    "-DCMAKE_TOOLCHAIN_FILE=${toolchain}"
    "-DANDROID_ABI=${abi}"
    "-DANDROID_PLATFORM=android-${platform}"
    "-DANDROID_STL=c++_static"
    "-DCMAKE_BUILD_TYPE=Release"
    # oboe (and snapcast) still declare cmake_minimum_required < 3.5, which
    # cmake 4.x refuses without this.
    "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
  ];

  # ---- static, bionic-targeted codec libraries -----------------------------
  flacS = cross.flac.overrideAttrs (o: {
    cmakeFlags = (o.cmakeFlags or []) ++ [
      "-DBUILD_SHARED_LIBS=OFF" "-DBUILD_PROGRAMS=OFF" "-DBUILD_EXAMPLES=OFF"
      "-DBUILD_TESTING=OFF" "-DBUILD_DOCS=OFF" "-DINSTALL_MANPAGES=OFF"
      "-DBUILD_CXXLIBS=OFF" "-DWITH_OGG=OFF"
    ];
    # We disabled the CLI tools/manpages/docs, leaving flac's declared bin/man/doc
    # outputs empty; nix errors unless they exist.
    postInstall = (o.postInstall or "") + ''
      mkdir -p "$bin" "$man" "$doc"
    '';
  });
  oggS = cross.libogg.overrideAttrs (o: {
    cmakeFlags = (o.cmakeFlags or []) ++ [ "-DBUILD_SHARED_LIBS=OFF" ];
  });
  opusS = cross.libopus.overrideAttrs (o: {
    mesonFlags = (o.mesonFlags or []) ++ [ "-Ddefault_library=static" ];
  });
  soxrS = cross.soxr.overrideAttrs (o: {
    cmakeFlags = (o.cmakeFlags or []) ++ [
      "-DBUILD_SHARED_LIBS=OFF" "-DBUILD_TESTS=OFF" "-DWITH_OPENMP=OFF"
      "-DBUILD_EXAMPLES=OFF"
    ];
  });
  vorbisS = cross.libvorbis.overrideAttrs (o: {
    configureFlags = (o.configureFlags or []) ++ [ "--disable-shared" "--enable-static" ];
  });

  # ---- oboe (missing from nixpkgs) -----------------------------------------
  oboe = pkgs.stdenv.mkDerivation {
    pname = "oboe-android";
    version = "1.9.3";
    src = pkgs.fetchFromGitHub {
      owner = "google";
      repo = "oboe";
      rev = "1.9.3";
      hash = "sha256-eufZSOvPK+nRnvnq7p0u72rRV3frNG5npmurHnLhTf8=";
    };
    nativeBuildInputs = [ pkgs.cmake pkgs.ninja ];
    dontUseCmakeConfigure = true;
    buildPhase = ''
      runHook preBuild
      cmake -S . -B build -G Ninja ${lib.concatStringsSep " " androidCmakeFlags} \
        -DBUILD_SHARED_LIBS=OFF -DOBOE_BUILD_SAMPLES=OFF -DOBOE_BUILD_TESTS=OFF
      cmake --build build -j $NIX_BUILD_CORES
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      mkdir -p $out/lib $out/include
      cp build/liboboe.a $out/lib/
      cp -r include/oboe $out/include/
      runHook postInstall
    '';
  };

  # ---- CMake shim configs ----------------------------------------------------
  # snapcast's Android branch does CONFIG-mode find_package for lowercase package
  # names (oboe, flac, ogg, opus, soxr, vorbis, boost) that only exist in
  # snapdroid's prebuilt-dependency environment. Synthesise them, each exporting
  # the name::name imported target the CMakeLists links against.
  shims = pkgs.runCommand "snapclient-cmake-shims" { } ''
    imp() { # name  target  archive  includes(;-separated)
      local name="$1" target="$2" archive="$3" incs="$4"
      mkdir -p "$out/lib/cmake/$name"
      {
        echo "add_library($target STATIC IMPORTED)"
        echo "set_target_properties($target PROPERTIES"
        echo "  IMPORTED_LOCATION \"$archive\""
        echo "  INTERFACE_INCLUDE_DIRECTORIES \"$incs\")"
      } > "$out/lib/cmake/$name/''${name}Config.cmake"
    }

    imp flac   flac::flac     "${flacS.out}/lib/libFLAC.a"          "${flacS.dev}/include"
    imp ogg    ogg::ogg       "${oggS.out}/lib/libogg.a"            "${oggS.dev}/include"
    imp opus   opus::opus     "${opusS.out}/lib/libopus.a"          "${opusS.dev}/include;${opusS.dev}/include/opus"
    imp soxr   soxr::soxr     "${soxrS.out}/lib/libsoxr.a"          "${soxrS.dev}/include"
    imp oboe   oboe::oboe     "${oboe}/lib/liboboe.a"               "${oboe}/include"
    imp vorbis vorbis::vorbis "${vorbisS.out}/lib/libvorbis.a"      "${vorbisS.dev}/include"

    # boost is header-only for snapcast (asio); native headers are arch-agnostic.
    mkdir -p "$out/lib/cmake/boost"
    {
      echo "add_library(boost::boost INTERFACE IMPORTED)"
      echo "set_target_properties(boost::boost PROPERTIES"
      echo "  INTERFACE_INCLUDE_DIRECTORIES \"${pkgs.boost.dev}/include\")"
    } > "$out/lib/cmake/boost/boostConfig.cmake"
  '';

in
pkgs.stdenv.mkDerivation {
  pname = "snapclient-android";
  version = pkgs.snapcast.version;
  src = pkgs.snapcast.src; # rides the nixpkgs pin -> auto-updates

  # snapcast's Android branch hardwires Tremor, which nixpkgs doesn't package.
  # The ogg decoder builds equally against libvorbis (HAS_VORBIS), so point the
  # Android branch at that.
  postPatch = ''
    substituteInPlace CMakeLists.txt \
      --replace-fail "find_package(tremor REQUIRED CONFIG)" "find_package(vorbis REQUIRED CONFIG)" \
      --replace-fail "add_compile_definitions(HAS_TREMOR)" "add_compile_definitions(HAS_VORBIS)"
    substituteInPlace client/CMakeLists.txt \
      --replace-fail "tremor::tremor" "vorbis::vorbis"
  '';

  nativeBuildInputs = [ pkgs.cmake pkgs.ninja pkgs.pkg-config ];
  dontUseCmakeConfigure = true;

  buildPhase = ''
    runHook preBuild
    cmake -S . -B build -G Ninja ${lib.concatStringsSep " " androidCmakeFlags} \
      -DBUILD_CLIENT=ON -DBUILD_SERVER=OFF \
      -DBUILD_STATIC_LIBS=ON -DBUILD_SHARED_LIBS=OFF \
      -DBUILD_WITH_SSL=OFF -DBUILD_WITH_AVAHI=OFF -DBUILD_WITH_EXPAT=OFF \
      -DBUILD_WITH_FLAC=ON -DBUILD_WITH_OPUS=ON \
      -DBUILD_WITH_VORBIS=ON \
      -DCMAKE_PREFIX_PATH="${shims}" \
      -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH
    cmake --build build -j $NIX_BUILD_CORES
    runHook postBuild
  '';

  installPhase = ''
    runHook preInstall
    mkdir -p "$out/jniLibs/${abi}"
    # snapcast's client target is already named libsnapclient.so on Android.
    bin=$(find build -name 'libsnapclient.so' -type f | head -1)
    "${ndkTool}/bin/llvm-strip" "$bin" -o "$out/jniLibs/${abi}/libsnapclient.so"
    runHook postInstall
  '';

  # The output is a stripped, cross-built aarch64 binary; skip the host hooks.
  dontStrip = true;
  dontPatchELF = true;

  meta = {
    # Built on the host (x86_64) via the NDK; the output targets aarch64-android.
    # Don't restrict meta.platforms or nix treats it as unsupported on the builder.
    description = "snapcast snapclient, cross-built for aarch64-android";
    homepage = "https://github.com/snapcast/snapcast";
    license = lib.licenses.gpl3Plus;
  };
}
