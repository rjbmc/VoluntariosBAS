package util.sevilla.bancodealimentos.es;

/**
 * Clase POJO (Plain Old Java Object) que representa una fila en el informe de asignaciones.
 * Se utiliza para facilitar la conversión automática a JSON mediante la librería Jackson.
 */
public class AsignacionRow {
    
    private String usuario;
    private String nombreCompleto;
    private String email;
    private String campana;
    
    // Datos del Turno 1
    private int turno1; // ID de tienda o 0 si no tiene
    private String nombreTienda1;
    
    // Datos del Turno 2
    private int turno2;
    private String nombreTienda2;
    
    // Puedes extender esto para Turno 3 y 4 si es necesario en el futuro
    private int turno3;
    private String nombreTienda3;
    
    private int turno4;
    private String nombreTienda4;

    // Constructor vacío (Requerido por algunas librerías de serialización, aunque Jackson es flexible)
    public AsignacionRow() {
    }

    // --- Getters y Setters ---

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getNombreCompleto() {
        return nombreCompleto;
    }

    public void setNombreCompleto(String nombreCompleto) {
        this.nombreCompleto = nombreCompleto;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCampana() {
        return campana;
    }

    public void setCampana(String campana) {
        this.campana = campana;
    }

    public int getTurno1() {
        return turno1;
    }

    public void setTurno1(int turno1) {
        this.turno1 = turno1;
    }

    public String getNombreTienda1() {
        return nombreTienda1;
    }

    public void setNombreTienda1(String nombreTienda1) {
        this.nombreTienda1 = nombreTienda1;
    }

    public int getTurno2() {
        return turno2;
    }

    public void setTurno2(int turno2) {
        this.turno2 = turno2;
    }

    public String getNombreTienda2() {
        return nombreTienda2;
    }

    public void setNombreTienda2(String nombreTienda2) {
        this.nombreTienda2 = nombreTienda2;
    }

    public int getTurno3() {
        return turno3;
    }

    public void setTurno3(int turno3) {
        this.turno3 = turno3;
    }

    public String getNombreTienda3() {
        return nombreTienda3;
    }

    public void setNombreTienda3(String nombreTienda3) {
        this.nombreTienda3 = nombreTienda3;
    }

    public int getTurno4() {
        return turno4;
    }

    public void setTurno4(int turno4) {
        this.turno4 = turno4;
    }

    public String getNombreTienda4() {
        return nombreTienda4;
    }

    public void setNombreTienda4(String nombreTienda4) {
        this.nombreTienda4 = nombreTienda4;
    }

    @Override
    public String toString() {
        return "AsignacionRow [usuario=" + usuario + ", nombreCompleto=" + nombreCompleto + ", email=" + email + ", campana=" + campana + "]";
    }
}