// Copyright (C) 2026 The joan-volte-lineage authors
// SPDX-License-Identifier: Apache-2.0

// Android 15's Soong over the Android.bp files the LineageOS 22.2 kit
// syncs: ImsStack and ImsMedia from Android 17 with the kit's patches.
//
// Every module type, property, select() and product variable in them has
// to mean something to Android 15's Soong, and every dependency has to
// resolve, or a LineageOS 22.2 tree fails before it compiles a line. The
// modules they use from the rest of the tree are stand-ins (stubs.bp),
// each one found in android15-qpr2-release by check-soong.sh
// --verify-stubs.
//
// tests/check-soong.sh copies this into Android 15's build/soong as
// package imscheck and runs it; IMSSTACK, IMSMEDIA and STUBS name the
// inputs.
package imscheck

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	_ "android/soong/aconfig"
	"android/soong/aconfig/codegen"
	"android/soong/android"
	"android/soong/etc"
	"android/soong/genrule"
	"android/soong/java"

	"github.com/google/blueprint/proptools"
)

// tree puts a source tree into the mock file system at dst: Android.bp
// and XML files with their contents, everything else as present.
func tree(t *testing.T, src, dst string) android.MockFS {
	fs := android.MockFS{}
	err := filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			if info.Name() == ".git" {
				return filepath.SkipDir
			}
			return nil
		}
		rel, _ := filepath.Rel(src, p)
		var data []byte
		if info.Name() == "Android.bp" || strings.HasSuffix(p, ".xml") {
			if data, err = os.ReadFile(p); err != nil {
				return err
			}
		}
		fs[filepath.Join(dst, rel)] = data
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return fs
}

func env(t *testing.T, name string) string {
	v := os.Getenv(name)
	if v == "" {
		t.Fatalf("%s is not set", name)
	}
	return v
}

// kit is the fixture: the two trees and the stand-ins, on an Android 15
// release configuration (SDK 35, REL).
func kit(t *testing.T, debuggable bool, edit func(android.MockFS)) android.FixturePreparer {
	fs := android.MockFS{}
	fs.Merge(tree(t, env(t, "IMSSTACK"), "packages/modules/ImsStack"))
	fs.Merge(tree(t, env(t, "IMSMEDIA"), "packages/modules/ImsMedia"))
	if edit != nil {
		edit(fs)
	}
	stubs, err := os.ReadFile(env(t, "STUBS"))
	if err != nil {
		t.Fatal(err)
	}
	return android.GroupFixturePreparers(
		java.PrepareForIntegrationTestWithJava,
		android.PrepareForTestWithLicenses,
		android.PrepareForTestWithLicenseDefaultModules,
		codegen.PrepareForTestWithAconfigBuildComponents,
		etc.PrepareForTestWithPrebuiltEtc,
		genrule.PrepareForTestWithGenRuleBuildComponents,
		fs.AddToFixture(),
		// What the test framework's own auto-generated RROs ask for on a
		// REL configuration.
		android.MockFS{
			"prebuilts/sdk/34/public/android.jar":                 nil,
			"prebuilts/sdk/34/public/framework.aidl":              nil,
			"prebuilts/sdk/34/public/core-for-system-modules.jar": nil,
			"prebuilts/sdk/35/public/android.jar":                 nil,
			"prebuilts/sdk/35/public/framework.aidl":              nil,
		}.AddToFixture(),
		android.FixtureAddTextFile("stubs/Android.bp", string(stubs)),
		android.FixtureModifyProductVariables(func(v android.FixtureProductVariables) {
			sdk := 35
			v.Platform_sdk_version = &sdk
			v.Platform_sdk_codename = proptools.StringPtr("REL")
			v.Platform_sdk_final = proptools.BoolPtr(true)
			v.Platform_version_active_codenames = nil
			v.Debuggable = proptools.BoolPtr(debuggable)
		}),
	)
}

func checkKit(t *testing.T, debuggable bool) {
	res := kit(t, debuggable, nil).RunTest(t)
	for _, m := range []string{"ImsStack", "ImsMediaService", "libimsstack", "libimsmedia",
		"imsstack_flags_java_lib", "preinstalled-packages-imsmedia.xml"} {
		if len(res.ModuleVariantsForTests(m)) == 0 {
			t.Errorf("module %s missing", m)
		}
	}
}

// userdebug and eng builds take the product_variables.debuggable branches.
func TestKitUserdebug(t *testing.T) { checkKit(t, true) }
func TestKitUser(t *testing.T)      { checkKit(t, false) }

// The check is live: a property Android 15's Soong does not know fails it.
func TestUnknownPropertyFails(t *testing.T) {
	bp := "packages/modules/ImsStack/java/Android.bp"
	kit(t, false, func(fs android.MockFS) {
		fs[bp] = []byte(strings.Replace(string(fs[bp]), "platform_apis: true,",
			"platform_apis: true,\n    aosp_ims_check_is_live: true,", 1))
	}).ExtendWithErrorHandler(android.FixtureExpectsAtLeastOneErrorMatchingPattern(
		`unrecognized property "aosp_ims_check_is_live"`)).RunTest(t)
}
