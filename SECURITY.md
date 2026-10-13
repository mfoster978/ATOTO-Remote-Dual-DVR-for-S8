# Security

## Updating safely

Camera Link accepts an in-app update only when the downloaded APK has the same package name and signing certificate as the installed release and has a higher version code. Android still presents its normal installation confirmation.

The release signing keystore is private and is not stored in this repository. Losing it would prevent a future release from updating existing installations.

## Network exposure

Keep the radio server on a private Tailscale network. Do not forward TCP port 8765 from a public router or expose it directly to the Internet. Treat setup QR codes, connection PINs, website passwords, and Tailscale IP addresses as private credentials.

## Reporting a vulnerability

Open a GitHub security advisory for sensitive reports if that option is available on the repository. Avoid posting working credentials, exact vehicle locations, camera footage, or connection QR codes in a public issue.
