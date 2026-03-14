{
  description = "Heron dev environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.clojure
            pkgs.jdk21
            pkgs.clj-kondo
          ];

          shellHook = ''
            echo "Heron dev shell ready (Clojure $(clojure --version 2>&1 | head -1), Java $(java -version 2>&1 | head -1))"
          '';
        };
      });
}
