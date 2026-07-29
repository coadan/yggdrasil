.PHONY: build check test benchmark-check benchmark-quick benchmark-dogfood-check benchmark-dogfood release clean

build:
	mkdir -p bin
	go build -trimpath -o bin/ygg ./cmd/ygg
	go build -trimpath -o bin/yggbench ./cmd/yggbench
	cd plugins/markdown && go build -trimpath -o ../../bin/ygg-extract-markdown .

test:
	go test ./...
	cd plugins/markdown && go test ./...

check:
	test -z "$$(gofmt -l $$(git ls-files '*.go'))"
	go vet ./...
	cd plugins/markdown && go vet ./...
	$(MAKE) test

benchmark-quick: build
	bin/yggbench -prepare -suite benchmarks/claim-quick.json -ygg bin/ygg

benchmark-check: build
	bin/yggbench -prepare -check-only -suite benchmarks/claim-quick.json -ygg bin/ygg

benchmark-dogfood: build
	bin/yggbench -prepare -suite benchmarks/dogfood-replay.json -ygg bin/ygg -out .dev/bench/dogfood-report.json

benchmark-dogfood-check: build
	bin/yggbench -prepare -check-only -suite benchmarks/dogfood-replay.json -ygg bin/ygg

release:
	mkdir -p dist
	CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/ygg-darwin-arm64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=darwin GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/ygg-darwin-amd64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/ygg-linux-arm64 ./cmd/ygg
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/ygg-linux-amd64 ./cmd/ygg
	cd dist && shasum -a 256 ygg-* > SHA256SUMS

clean:
	rm -f bin/ygg bin/yggbench bin/ygg-extract-markdown
	rm -f dist/ygg-darwin-arm64 dist/ygg-darwin-amd64
	rm -f dist/ygg-linux-arm64 dist/ygg-linux-amd64
	rm -f dist/SHA256SUMS
