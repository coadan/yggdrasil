.PHONY: build check test benchmark-check benchmark-quick release clean

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

release:
	mkdir -p dist
	GOOS=darwin GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/ygg-darwin-arm64 ./cmd/ygg
	GOOS=darwin GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/ygg-darwin-amd64 ./cmd/ygg
	GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/ygg-linux-arm64 ./cmd/ygg
	GOOS=linux GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/ygg-linux-amd64 ./cmd/ygg

clean:
	rm -f bin/ygg bin/yggbench bin/ygg-extract-markdown
	rm -f dist/ygg-darwin-arm64 dist/ygg-darwin-amd64
	rm -f dist/ygg-linux-arm64 dist/ygg-linux-amd64
