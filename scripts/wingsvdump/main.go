package main

import (
	"bytes"
	"compress/zlib"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	wingsvproto "github.com/mhsanaei/3x-ui/v2/wingsv/proto"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

const frameProtoDeflate = 0x12

func decodeToken(uri string) (*wingsvproto.Config, error) {
	raw := strings.TrimSpace(uri)
	raw = strings.TrimPrefix(raw, "wingsv://")
	raw = strings.ReplaceAll(raw, "\n", "")
	raw = strings.ReplaceAll(raw, "\r", "")
	raw = strings.ReplaceAll(raw, " ", "")

	b, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		b, err = base64.URLEncoding.DecodeString(raw)
	}
	if err != nil {
		return nil, fmt.Errorf("base64: %w", err)
	}
	if len(b) == 0 || b[0] != frameProtoDeflate {
		return nil, fmt.Errorf("bad framing: want 0x12 prefix")
	}

	zr, err := zlib.NewReader(bytes.NewReader(b[1:]))
	if err != nil {
		return nil, fmt.Errorf("zlib: %w", err)
	}
	defer zr.Close()
	rawProto, err := io.ReadAll(zr)
	if err != nil {
		return nil, fmt.Errorf("inflate: %w", err)
	}

	cfg := &wingsvproto.Config{}
	if err := proto.Unmarshal(rawProto, cfg); err != nil {
		return nil, fmt.Errorf("proto: %w", err)
	}
	return cfg, nil
}

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: wingsvdump 'wingsv://...'")
		os.Exit(2)
	}
	cfg, err := decodeToken(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	out, err := protojson.MarshalOptions{Indent: "  "}.Marshal(cfg)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	var compact interface{}
	if err := json.Unmarshal(out, &compact); err == nil {
		pretty, _ := json.MarshalIndent(compact, "", "  ")
		os.Stdout.Write(pretty)
		os.Stdout.Write([]byte{'\n'})
		return
	}
	os.Stdout.Write(out)
	os.Stdout.Write([]byte{'\n'})
}
