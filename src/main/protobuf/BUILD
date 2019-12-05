package(default_visibility = ["//visibility:public"])

exports_files(
    ["execution_statitics.proto"],
    visibility = [
        "//src/test/shell/integration:process_wrapper_test",
    ],
)

FILES = [
    "action_cache",
    "android_deploy_info",
    "bazel_flags",
    "build",
    "builtin",
    "command_server",
    "crosstool_config",
    "deps",
    "desugar_deps",
    "execution_statistics",
    "extra_actions_base",
    "invocation_policy",
    "java_compilation",
    "spawn",
    "test_status",
    "worker_protocol",
]

[proto_library(
    name = s + "_proto",
    srcs = [s + ".proto"],
) for s in FILES]

proto_library(
    name = "analysis_proto",
    srcs = ["analysis.proto"],
    deps = [":build_proto"],
)

proto_library(
    name = "analysis_v2_proto",
    srcs = ["analysis_v2.proto"],
    deps = [":build_proto"],
)

# This new option tagging method is in flux while being applied to the options
# in the Bazel code base. The visibility should not be changed to allow external
# dependencies until the interface has stabilized and can commit to maintaining
# backwards compatibility for 6 months' time.
# TODO(bazel-team) Make these visibility:public when the interface is stable.
proto_library(
    name = "option_filters_proto",
    srcs = ["option_filters.proto"],
    visibility = ["//visibility:private"],
)

proto_library(
    name = "command_line_proto",
    srcs = ["command_line.proto"],
    visibility = ["//src/main/java/com/google/devtools/build/lib/buildeventstream/proto:__pkg__"],
    deps = [":option_filters_proto"],
)

# Proto needed by singlejar and embedded into the java tools archive.
genrule(
    name = "desugar_deps_zip",
    srcs = ["desugar_deps.proto"],
    outs = ["desugar_deps.zip"],
    cmd = "zip -q $@ $<",
    visibility = ["//src/tools/singlejar:__pkg__"],
)

filegroup(
    name = "desugar_deps_filegroup",
    srcs = ["desugar_deps.proto"],
    visibility = ["//src/tools/singlejar:__pkg__"],
)

proto_library(
    name = "remote_execution_log_proto",
    srcs = ["remote_execution_log.proto"],
    deps = [
        "@com_google_protobuf//:timestamp_proto",
        "@googleapis//:google_bytestream_bytestream_proto",
        "@googleapis//:google_longrunning_operations_proto",
        "@googleapis//:google_rpc_status_proto",
        "@remoteapis//:build_bazel_remote_execution_v2_remote_execution_proto",
    ],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

filegroup(
    name = "dist_jars",
    srcs = [s + "_java_proto_srcs" for s in FILES] + [
        ":analysis_java_proto_srcs",
        ":analysis_v2_java_proto_srcs",
        ":command_line_java_proto_srcs",
        ":command_server_java_grpc_srcs",
        ":option_filters_java_proto_srcs",
        ":profile_java_proto_srcs",
        ":remote_execution_log_java_proto_srcs",
    ],
)

load("@io_bazel_rules_go//go:def.bzl", "go_library")
load("@io_bazel_rules_go//proto:def.bzl", "go_proto_library")

go_proto_library(
    name = "command_line_go_proto",
    importpath = "github.com/bazelbuild/bazel/src/main/protobuf",
    proto = ":command_line_proto",
)

go_proto_library(
    name = "invocation_policy_go_proto",
    importpath = "github.com/bazelbuild/bazel/src/main/protobuf",
    proto = ":invocation_policy_proto",
)

go_proto_library(
    name = "option_filters_go_proto",
    importpath = "github.com/bazelbuild/bazel/src/main/protobuf",
    proto = ":option_filters_proto",
)

go_library(
    name = "go_default_library",
    embed = [
        ":command_line_go_proto",
        ":option_filters_go_proto",
        ":invocation_policy_go_proto",
    ],
    importpath = "github.com/bazelbuild/bazel/src/main/protobuf",
    visibility = ["//visibility:public"],
)
