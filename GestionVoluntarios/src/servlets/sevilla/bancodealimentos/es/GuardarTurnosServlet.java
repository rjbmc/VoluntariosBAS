// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet que guarda la asignaci�n de turnos.
 * Si es llamado por un admin, guarda los turnos para el usuario especificado.
 * Si no, guarda los turnos para el usuario en sesi�n.
 */
@WebServlet("/guardar-turnos")
public class GuardarTurnosServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versi�n actualizada

    private boolean isAdmin(HttpSession session) {
        if (session == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        return isAdminAttr != null && (boolean) isAdminAttr;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        String jsonResponse;

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesi�n de usuario activa.");
            return;
        }

        // --- CAMBIO: L�gica para determinar para qu� usuario se guardan los turnos ---
        String usuarioAGuardar = request.getParameter("usuario");
        String usuarioEnSesion = (String) session.getAttribute("usuario");
        String usuarioFinal;

        if (isAdmin(session) && usuarioAGuardar != null && !usuarioAGuardar.trim().isEmpty()) {
            // Un administrador est� guardando los datos de un voluntario espec�fico.
            usuarioFinal = usuarioAGuardar;
        } else {
            // Un voluntario normal guarda sus propios datos.
            usuarioFinal = usuarioEnSesion;
        }
        // --- FIN DEL CAMBIO ---

        try {
            String campanaId = request.getParameter("campanaId");

            Integer[] tiendas = new Integer[4];
            String[] comentarios = new String[4];

            for (int i = 1; i <= 4; i++) {
                String tiendaStr = request.getParameter("tienda_" + i);
                if (tiendaStr != null && !tiendaStr.isEmpty()) {
                    tiendas[i - 1] = Integer.parseInt(tiendaStr);
                    String acompanantesStr = request.getParameter("acompanantes_" + i);
                    int acompanantes = (acompanantesStr != null && !acompanantesStr.isEmpty()) ? Integer.parseInt(acompanantesStr) : 0;
                    String comentarioTexto = request.getParameter("comentario_" + i);
                    
                    StringBuilder comentarioFinal = new StringBuilder();
                    if (acompanantes > 0) {
                        comentarioFinal.append("Voluntarios: ").append(acompanantes);
                        if (comentarioTexto != null && !comentarioTexto.trim().isEmpty()) {
                            comentarioFinal.append(". ").append(comentarioTexto.trim());
                        }
                    } else if (comentarioTexto != null && !comentarioTexto.trim().isEmpty()) {
                        comentarioFinal.append(comentarioTexto.trim());
                    }
                    comentarios[i - 1] = comentarioFinal.toString();
                } else {
                    tiendas[i - 1] = null;
                    comentarios[i - 1] = "";
                }
            }
            
            String sql = "INSERT INTO voluntarios_en_campana (Campana, Usuario, Turno1, Comentario1, Turno2, Comentario2, Turno3, Comentario3, Turno4, Comentario4, notificar) " +
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                         "ON DUPLICATE KEY UPDATE " +
                         "Turno1 = VALUES(Turno1), Comentario1 = VALUES(Comentario1), " +
                         "Turno2 = VALUES(Turno2), Comentario2 = VALUES(Comentario2), " +
                         "Turno3 = VALUES(Turno3), Comentario3 = VALUES(Comentario3), " +
                         "Turno4 = VALUES(Turno4), Comentario4 = VALUES(Comentario4), " +
                         "notificar = VALUES(notificar)";

            try (Connection conn = DatabaseUtil.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, campanaId);
                stmt.setString(2, usuarioFinal);
                stmt.setObject(3, tiendas[0], java.sql.Types.INTEGER);
                stmt.setString(4, comentarios[0]);
                stmt.setObject(5, tiendas[1], java.sql.Types.INTEGER);
                stmt.setString(6, comentarios[1]);
                stmt.setObject(7, tiendas[2], java.sql.Types.INTEGER);
                stmt.setString(8, comentarios[2]);
                stmt.setObject(9, tiendas[3], java.sql.Types.INTEGER);
                stmt.setString(10, comentarios[3]);
                stmt.setString(11, "S");

                int filasAfectadas = stmt.executeUpdate();
                
                if (filasAfectadas > 0) {
                    String logComment = isAdmin(session) ? "Admin " + usuarioEnSesion + " modific� los turnos de " + usuarioFinal : "Guardado/Modificaci�n de turnos para la campa�a " + campanaId;
                    LogUtil.logOperation(conn, "ASIGNACION", usuarioEnSesion, logComment);
                    jsonResponse = "{\"success\": true, \"message\": \"�Turnos guardados con �xito!\"}";
                } else {
                    jsonResponse = "{\"success\": true, \"message\": \"Los turnos no han cambiado.\"}";
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse = "{\"success\": false, \"message\": \"Error al guardar los datos en la base de datos.\"}";
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse = "{\"success\": false, \"message\": \"Error en los datos enviados.\"}";
        }

        out.print(jsonResponse);
        out.flush();
    }
}
