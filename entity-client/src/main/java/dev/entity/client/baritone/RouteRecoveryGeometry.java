package dev.entity.client.baritone;

/** Geometry for choosing a different approach, not a replacement pathfinder. */
public final class RouteRecoveryGeometry {
    private RouteRecoveryGeometry() { }

    public record Cell(int x, int y, int z) { }

    /** A closed door at the destination of the current one-block ascending edge. */
    public static boolean ascendingPortalEntry(Cell source,Cell destination,Cell portal,int nx,int ny,int nz) {
        if(source==null || destination==null || portal==null || !destination.equals(portal)
                || destination.y()!=source.y()+1 || ny!=0 || Math.abs(nx)+Math.abs(nz)!=1) return false;
        int dx=destination.x()-source.x(),dz=destination.z()-source.z();
        return Math.abs(dx)+Math.abs(dz)==1 && Math.abs(dx*nx+dz*nz)==1;
    }

    /** Native Ascend and Diagonal omit the ordinary flat Traverse door click. */
    public static boolean nativePortalEntry(Cell source,Cell destination,Cell portal,int nx,int ny,int nz) {
        if(ascendingPortalEntry(source,destination,portal,nx,ny,nz))return true;
        if(source==null||destination==null||portal==null||!destination.equals(portal)
                ||destination.y()!=source.y()||ny!=0||Math.abs(nx)+Math.abs(nz)!=1)return false;
        int dx=destination.x()-source.x(),dz=destination.z()-source.z();
        return Math.abs(dx)==1&&Math.abs(dz)==1&&Math.abs(dx*nx+dz*nz)==1;
    }

    /** Approach an observed horizontal portal from this side, only for a goal beyond it. */
    public static Cell portalApproach(int x, int y, int z, int nx, int nz,
            double bodyX, double bodyZ, double goalX, double goalZ) {
        if (Math.abs(nx) + Math.abs(nz) != 1) return null;
        double bodySide = (bodyX - (x + 0.5)) * nx + (bodyZ - (z + 0.5)) * nz;
        double goalSide = (goalX - (x + 0.5)) * nx + (goalZ - (z + 0.5)) * nz;
        if (Math.abs(bodySide) < 0.25 || Math.abs(goalSide) < 0.25
                || Math.signum(bodySide) == Math.signum(goalSide)) return null;
        int side = bodySide < 0 ? -1 : 1;
        return new Cell(x + nx * side * 2, y, z + nz * side * 2);
    }

    public static boolean clearsCircle(double ax, double az, double bx, double bz,
            double hazardX, double hazardZ, double radius) {
        double dx = bx - ax, dz = bz - az;
        double length = dx * dx + dz * dz;
        double t = length == 0 ? 0 : Math.max(0, Math.min(1,
                ((hazardX - ax) * dx + (hazardZ - az) * dz) / length));
        double x = ax + t * dx - hazardX, z = az + t * dz - hazardZ;
        return x * x + z * z >= radius * radius;
    }

    public static boolean alternative(double ax, double az, double bx, double bz,
            double hazardX, double hazardZ) {
        double oldDistance = Math.hypot(ax - hazardX, az - hazardZ);
        return Math.hypot(bx - ax, bz - az) >= 4
                && Math.hypot(bx - hazardX, bz - hazardZ) >= oldDistance + 2;
    }
}
