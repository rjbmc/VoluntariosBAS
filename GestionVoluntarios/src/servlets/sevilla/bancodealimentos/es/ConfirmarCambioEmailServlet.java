// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet que finaliza el proceso de cambio de email.
 * Verifica el token y actualiza el email del usuario.
 */
@WebServlet("/confirmar-cambio-email")
public class ConfirmarCambioEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        PrintWriter out = response.getWriter();
        String jsonResponse;

        String token = request.getParameter("token");

        if (token == null || token.trim().isEmpty()) {
            sendError(response, "Token no proporcionado.");
            return;
        }

        String sqlFindUser = "SELECT Usuario, nuevo_email FROM voluntarios WHERE token_cambio_email = ?";
        
        try (Connection conn = DatabaseUtil.getConnection()) {
            String usuario = null;
            String nuevoEmail = null;

            // 1. Buscar el usuario y el nuevo email por el token
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        nuevoEmail = rs.getString("nuevo_email");
                    }
                }
            }

            // 2. Si se encuentra un usuario, se actualiza su email
            if (usuario != null && nuevoEmail != null) {
                String sqlUpdate = "UPDATE voluntarios SET Email = ?, nuevo_email = NULL, token_cambio_email = NULL, notificar = 'S' WHERE Usuario = ?";

                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, nuevoEmail);
                    stmtUpdate.setString(2, usuario);
                    stmtUpdate.executeUpdate();
                    
                    // --- CAMBIO: Se elimina el �ltimo par�metro de la llamada al log ---
                    LogUtil.logOperation(conn, "CAMBIO_EMAIL_OK", usuario, "Email actualizado a: " + nuevoEmail);
                    jsonResponse = "{\"success\": true, \"message\": \"Tu direcci�n de correo ha sido actualizada con �xito.\"}";
                }
            } else {
                jsonResponse = "{\"success\": false, \"message\": \"El enlace de confirmaci�n no es v�lido o ya ha sido utilizado.\"}";
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            if (e.getErrorCode() == 1062) {
                 jsonResponse = "{\"success\": false, \"message\": \"La nueva direcci�n de correo ya est� en uso por otro voluntario.\"}";
            } else {
                 jsonResponse = "{\"success\": false, \"message\": \"Error de base de datos al actualizar el email.\"}";
            }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }

        out.print(jsonResponse);
        out.flush();
    }

    private void sendError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.getWriter().print("{\"success\": false, \"message\": \"" + message + "\"}");
    }
}
