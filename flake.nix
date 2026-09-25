{
  description = "Development shell for ores-comparisons";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            age sops jq yq git gnused coreutils curl
            nodejs_22 postgresql_16 protobuf
            rustc cargo gleam docker
          ];
          shellHook = ''
            echo "ores-comparisons shell: contracts + sops/age + postgres + rust/gleam"
            echo "install pinned ores-compose with ./scripts/install-ores-compose.sh"
          '';
        };
      });
    };
}
