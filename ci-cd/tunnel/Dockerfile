# Separate image: no Java, application files, volume, or application secrets.
# Deliberate version upgrades; no runtime self-updater or floating latest tag.
FROM cloudflare/cloudflared:2026.10.0
USER 65532:65532
ENTRYPOINT ["cloudflared"]
# cloudflared reads TUNNEL_TOKEN directly from the runtime environment.
# Metrics/readiness are private: fly.toml publishes no services.
CMD ["tunnel", "--no-autoupdate", "--metrics", "[::]:2000", "--loglevel", "info", "run"]
